#!/bin/bash
# 调用阿里云百炼多模态 API 识别图片
# 用法: ./dashscope-vision.sh <图片URL> [问题描述]
# 示例: ./dashscope-vision.sh https://example.com/cat.jpg "这张图里有什么?"

set -euo pipefail

IMAGE_URL="$1"
QUESTION="${2:-请描述这张图片的内容}"

if [ -z "$IMAGE_URL" ]; then
    echo "❌ 请提供图片 URL"
    echo "用法: $0 <图片URL> [问题]"
    exit 1
fi

# 获取 API Key（优先用环境变量，fallback 到 .zshrc）
API_KEY="${DASHSCOPE_API_KEY:-}"
if [ -z "$API_KEY" ]; then
    # shellcheck disable=SC1091
    source /Users/lienqi/.zshrc 2>/dev/null || true
    API_KEY="${DASHSCOPE_API_KEY:-}"
fi

if [ -z "$API_KEY" ]; then
    echo "❌ 未设置 DASHSCOPE_API_KEY 环境变量"
    exit 1
fi

# 下载图片
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT
TMPFILE="$TMPDIR/image"

echo "📥 下载图片..."
curl -sL -o "$TMPFILE" "$IMAGE_URL" --connect-timeout 15 --max-time 60
if [ ! -s "$TMPFILE" ]; then
    echo "❌ 图片下载失败"
    exit 1
fi

# 检测真实格式
MIME=$(file -b --mime-type "$TMPFILE")
echo "✅ 图片下载完成 ($(ls -lh "$TMPFILE" | awk '{print $5}'), $MIME)"

# 非图片则报错
if ! echo "$MIME" | grep -qE "^(image/jpeg|image/png|image/gif|image/webp)"; then
    echo "❌ 下载的不是图片，可能是防盗链或404页面"
    exit 1
fi

# 转 base64
B64=$(base64 -i "$TMPFILE")
DATA_URL="data:${MIME};base64,$B64"

echo "🔍 正在识别..."

# 调用 API
curl -s -X POST "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $API_KEY" \
    -d "{
        \"model\": \"qwen-vl-plus\",
        \"input\": {
            \"messages\": [{
                \"role\": \"user\",
                \"content\": [
                    {\"image\": \"$DATA_URL\"},
                    {\"text\": \"$QUESTION\"}
                ]
            }]
        }
    }" \
    --connect-timeout 15 --max-time 120 | python3 -c "
import sys, json
data = json.load(sys.stdin)
choices = data.get('output', {}).get('choices', [])
if not choices:
    err = data.get('message', data.get('code', '未知错误'))
    print(f'❌ {err}')
    sys.exit(1)
content = choices[0].get('message', {}).get('content', '')
if isinstance(content, list):
    # content 是 [{text: '...'}] 格式
    texts = [c.get('text', '') for c in content if isinstance(c, dict)]
    print('\n'.join(texts))
elif isinstance(content, str):
    print(content)
else:
    print(str(content))
" 2>&1

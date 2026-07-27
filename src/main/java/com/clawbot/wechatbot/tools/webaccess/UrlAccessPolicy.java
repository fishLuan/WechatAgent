package com.clawbot.wechatbot.tools.webaccess;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

public class UrlAccessPolicy implements UriAccessValidator {
    private static final int MAX_URL_LENGTH = 4096;

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Set<Integer> allowedPorts;
    private final HostResolver resolver;

    public UrlAccessPolicy(Set<Integer> allowedPorts) {
        this(allowedPorts, InetAddress::getAllByName);
    }

    public UrlAccessPolicy(Set<Integer> allowedPorts, HostResolver resolver) {
        this.allowedPorts = Set.copyOf(allowedPorts);
        this.resolver = resolver;
    }

    @Override
    public void validate(URI uri) throws UnsafeUrlException {
        if (uri == null) throw new UnsafeUrlException("URL 不能为空");
        if (uri.toString().length() > MAX_URL_LENGTH) {
            throw new UnsafeUrlException("URL 长度超过系统限制");
        }

        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new UnsafeUrlException("仅允许访问 http/https URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UnsafeUrlException("URL 不允许包含用户名或密码");
        }

        String host = lower(uri.getHost());
        if (host.isBlank()) throw new UnsafeUrlException("URL 缺少有效域名");
        if ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")) {
            throw new UnsafeUrlException("禁止访问本机或本地域名");
        }

        int effectivePort = uri.getPort() == -1
            ? ("https".equals(scheme) ? 443 : 80)
            : uri.getPort();
        if (!allowedPorts.contains(effectivePort)) {
            throw new UnsafeUrlException("禁止访问端口：" + effectivePort);
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("域名解析失败：" + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnsafeUrlException("域名没有可用 IP 地址：" + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new UnsafeUrlException(
                    "目标域名解析到非公网地址，已拒绝访问：" + address.getHostAddress());
            }
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);

            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && b == 168) return false;
            if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;
            if (a == 198 && (b == 18 || b == 19 || b == 51)) return false;
            if (a == 203 && b == 0 && c == 113) return false;
            return true;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xFE) == 0xFC) return false; // fc00::/7
            if (first == 0xFE && (second & 0xC0) == 0x80) return false; // fe80::/10
            return !isIpv4MappedPrivate(bytes);
        }
        return false;
    }

    private boolean isIpv4MappedPrivate(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        if (Byte.toUnsignedInt(bytes[10]) != 0xFF
                || Byte.toUnsignedInt(bytes[11]) != 0xFF) {
            return false;
        }
        byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        try {
            return !isPublicAddress(InetAddress.getByAddress(ipv4));
        } catch (UnknownHostException impossible) {
            return true;
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

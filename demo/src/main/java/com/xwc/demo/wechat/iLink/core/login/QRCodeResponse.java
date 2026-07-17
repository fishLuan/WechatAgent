package com.xwc.demo.wechat.iLink.core.login;

public class QRCodeResponse {
  private final String qrcode;
  private final String qrcodeImgContent;

  public QRCodeResponse(String qrcode, String qrcodeImgContent) {
    this.qrcode = qrcode;
    this.qrcodeImgContent = qrcodeImgContent;
  }

  public String getQrcode() {
    return qrcode;//状态码
  }

  public String getQrcodeImgContent() {
    return qrcodeImgContent;//二维码图片内容
  }
}

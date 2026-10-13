# 签名密钥（发布用）

`dreamgirl.jks` 是本项目的**发布签名密钥**。用它签出来的包与既有安装**签名一致**，
因此可以**覆盖升级**（不丢数据）。

- alias: `dreamgirl`
- storePassword / keyPassword: `dreamgirl`
- SHA256 指纹: `FB:87:75:10:5B:E0:14:ED:7F:36:54:06:D4:1C:F0:B8:B2:42:87:D3:C3:1D:DC:72:3D:B6:A2:37:C9:31:C6:7B`
- 有效期至 2054-02-26

## 用法

在项目根目录放一份 `keystore.properties`（已随本目录提供），`assembleRelease` 会自动读取。

## ⚠️ 警告

**签名密钥是"这个 App 的身份"。** 拿到它的人可以签出系统认可的包，
覆盖安装到任何已安装本 App 的设备上。请勿在不信任的环境分发。

# Server mode (FTP / SFTP / web)

Built-in share servers are **off by default**.

| Server | Library | Default port |
|--------|---------|--------------|
| FTP | Apache FtpServer 1.2 (Apache-2.0) | 2121 |
| SFTP | Apache MINA SSHD 2.12 (Apache-2.0) | 2222 |
| Web | Existing NanoHTTPD-based `WifiTransferServer` (improved HTML) | 8765 |

## Security defaults
- Bind to **LAN IPv4** only (`WifiTransferServer.lanIpv4`)
- Random password / PIN on start
- SFTP optional authorized public keys
- Stop when leaving the screen / `stopAll()`

## QR
On-screen QR encodes the connection URL (same ZXing path as Wi-Fi transfer).

## UI
Server Mode / Wi-Fi transfer sheet: start FTP, SFTP, or Web for the focused folder.

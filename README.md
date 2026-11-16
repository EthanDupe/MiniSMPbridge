# MiniSMPBridge

A lightweight PaperSpigot 1.12.2 plugin that embeds a non-blocking RakNet UDP listener (MCPE 1.1.5 / protocol 113) on port `25318` and bridges modded Nintendo 3DS 1.10 clients into a local Paper 1.12.2 server (`127.0.0.1:25318`, Java protocol 340).

## Features
- **Single-Port FalixNodes Support**: Binds UDP `25318` in-process alongside PaperSpigot's TCP `25318`.
- **MCPE 1.1.5 <-> Java 1.12.2 Translation**: RakNet handshake, JWT login, `StartGame`, `ChunkData` -> `FullChunkDataPacket`, `BlockChange`, `MovePlayer`, `ChatMessage`, `UpdateHealth`, and `KeepAlive`.
- **3DS `AddHangingEntityPacket` Fix**: Safely catches and ignores malformed `0x10` (`AddHangingEntityPacket`) and unknown 3DS packets without console spam or disconnects.
- **1 GB RAM Friendly**: Relocated Netty 4.1 + Cloudburst RakNet, zero chunk caching, 2-thread Netty pools, and configurable `max-view-distance`.

## Compile with GitHub Actions
1. Push this folder to a GitHub repository.
2. Open the **Actions** tab and run **Build MiniSMPBridge** (or push to `main`/`master`).
3. Download the `MiniSMPBridge-1.0.0` artifact containing `MiniSMPBridge-1.0.0.jar`.

## Local Maven Build
```bash
mvn clean package
```
Output: `target/MiniSMPBridge-1.0.0.jar`

## Server Setup (PaperSpigot 1.12.2)
1. Ensure `online-mode=false` and `server-port=25318` in `server.properties`.
2. Drop `MiniSMPBridge-1.0.0.jar` into `plugins/` and restart the server.

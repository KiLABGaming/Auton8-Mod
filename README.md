<table align="center" width="100%">
  <tr>
    <td align="center" width="130" style="min-width:130px;">
      <img src="src/main/resources/assets/template/icon.png" alt="Auton8 logo" width="110">
    </td>
    <td>

### Auton8 (Prototype)
#### Minecraft Automation with N8N and MQTT

**Status: Archived / Unmaintained**  
This project is a proof-of-concept for automating Minecraft gameplay using **n8n** (no/low-code workflows) over **MQTT** (Mosquitto) to a **Fabric** client mod that drives **Baritone**.
It is not production-ready. The goal is to inspire someone to **rebuild this idea properly**, with better UX, safer defaults, and a broader ecosystem.


  </tr>
</table>

## Quick Start (Windows)

### 1) Install Docker Desktop
Download & install:  
https://docs.docker.com/desktop/setup/install/windows-install/

### 2) Install Fabric, Baritone, and required mods (Minecraft 1.21.8)
Put these **four** files into your Minecraft **mods** folder:
> ⚠️ This prototype targets **Minecraft 1.21.8** specifically. Stick to matching Fabric/Mod versions.

Downloads:
- Baritone API (Fabric 1.15.0):  
  https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar
- Baritone Standalone (Fabric 1.15.0):  
  https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-standalone-fabric-1.15.0.jar
- Fabric API (for 1.21.8):  
  https://modrinth.com/mod/fabric-api?version=1.21.8#download
- Mod Menu (for 1.21.8):  
  https://modrinth.com/mod/modmenu?version=1.21.8&loader=fabric#download

Also place the **Auton8** mod JAR (from this repo’s Releases) into the same `mods` folder.

### 3) Start the local services (Mosquitto + n8n)
From the project root, double-click:
- `start-docker.bat`

This will run Docker Compose and bring up:
- **Mosquitto** on `127.0.0.1:1883`
- **n8n** on `http://localhost:5678`

On the first run, the stack auto-creates:
- an empty `mosquitto/config/passwd`
- `mosquitto/data/mosquitto.db` (persistence)

### 4) Open n8n and create an account
Visit:  
`http://localhost:5678`  
Create your n8n account and log in.

### 5) Launch Minecraft
Start your Fabric profile (1.21.8).  
Open Mod Menu you should see **Auton8** listed.  
The mod connects to MQTT and publishes/receives on the topics below.

---

## MQTT topics & default credentials (current prototype)

The current build has the following values hard-coded:  
You’ll need to use these when setting up your MQTT Trigger node in n8n.

```java
private static final String CLIENT_ID  = "kilab-pc1";
private static final String USERNAME   = "kilab-pc1";
private static final String PASSWORD   = "YOUR_SUPER_STRONG_PASSWORD";
private static final String CMD_TOPIC  = "mc/kilab-pc1/cmd";
private static final String EVT_TOPIC  = "mc/kilab-pc1/events";

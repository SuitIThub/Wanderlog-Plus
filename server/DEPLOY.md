# Automatisches Server-Deploy (Self-hosted Runner)

Ziel: Sobald server-relevanter Code auf `main` landet, baut und startet der Server sich selbst neu –
ohne dass du dich einloggen und `deploy.sh` manuell starten musst.

Wie es funktioniert: Auf dem Zielserver läuft ein **GitHub-Actions-Runner** als Dienst. Der meldet sich
ausgehend bei GitHub (kein offener SSH-Port nötig). Bei einem Push auf `main` führt der Workflow
[`.github/workflows/deploy-server.yml`](../.github/workflows/deploy-server.yml) auf diesem Runner das
bestehende `deploy.sh` aus (`git pull` + `docker compose up -d --build`).

> **Sicherheit (Repo ist öffentlich):** Der Workflow läuft **nur bei `push` auf `main`**, nie bei
> `pull_request`. Das ist Absicht und darf nicht geändert werden – sonst könnte jede Person per Fork-PR
> beliebigen Code auf deinem Server ausführen. Pushen auf `main` kann nur, wer Schreibrechte am Repo hat.

## Einmalige Einrichtung

### 1. Repository-Variable setzen
GitHub → **Settings → Secrets and variables → Actions → Variables → New repository variable**
- Name: `WLP_DEPLOY_DIR`
- Value: absoluter Pfad zum ausgecheckten Repo auf dem Server, also das Verzeichnis, in dem `deploy.sh`
  liegt. Beispiel: `/home/suit/Wanderlog-Plus`

### 2. Voraussetzungen auf dem Server prüfen
Als der Benutzer, unter dem der Runner laufen soll:
```bash
# Docker nutzbar ohne sudo? (sonst: sudo usermod -aG docker "$USER" und neu einloggen)
docker ps
# Deploy-Verzeichnis vorhanden und deploy.sh ausführbar?
cd /home/suit/Wanderlog-Plus && git status && ls -l deploy.sh
```

### 3. Runner herunterladen und registrieren
Die genauen Download-Befehle **und den Registrierungs-Token** holst du dir frisch unter:
GitHub → **Settings → Actions → Runners → New self-hosted runner** (Linux x64). Der Token ist nur kurz
gültig, deshalb steht er hier nicht. Beispielablauf:

```bash
mkdir -p ~/actions-runner && cd ~/actions-runner
# URL/Version/Hash aus der GitHub-Seite übernehmen:
curl -o actions-runner.tar.gz -L https://github.com/actions/runner/releases/download/vX.Y.Z/actions-runner-linux-x64-X.Y.Z.tar.gz
tar xzf actions-runner.tar.gz

# Registrieren – WICHTIG das Label "wlp-server" vergeben (darauf matcht der Workflow):
./config.sh \
  --url https://github.com/SuitIThub/Wanderlog-Plus \
  --token <TOKEN_VON_DER_GITHUB_SEITE> \
  --name wlp-server \
  --labels wlp-server \
  --unattended
```

### 4. Als Dienst installieren (läuft dann automatisch im Hintergrund, auch nach Reboot)
```bash
cd ~/actions-runner
sudo ./svc.sh install "$USER"   # Runner läuft unter diesem Benutzer
sudo ./svc.sh start
sudo ./svc.sh status
```

Danach erscheint der Runner in GitHub unter **Settings → Actions → Runners** als „Idle".

## Testen
- In der Actions-Oberfläche den Workflow **„Deploy Server"** auswählen → **Run workflow** (manuell), oder
- eine Kleinigkeit unter `server/` committen und auf `main` pushen.

Der Lauf sollte grün sein und am Ende `docker compose ... ps` zeigen.

## Hinweise / Fallstricke
- **`git pull --ff-only`** in `deploy.sh` schlägt fehl, wenn der Server-Checkout lokale, nicht gepushte
  Commits oder uncommittete Änderungen an versionierten Dateien hat. `server/.env`,
  `server/docker-compose.override.yml` und `server/Caddyfile` sind lokal/ignoriert und stören nicht.
- Der Runner nutzt sein eigenes Arbeitsverzeichnis, deployt aber bewusst im Verzeichnis `WLP_DEPLOY_DIR`.
  So bleiben die lokale `override.yml`, `.env` und die vorhandenen Docker-Volumes unverändert erhalten
  (gleicher Compose-Projektname wie bei deinem bisherigen manuellen Deploy).
- Logs des Runners: `journalctl -u actions.runner.* -f` oder im Ordner `~/actions-runner/_diag/`.
- Runner entfernen: `sudo ./svc.sh stop && sudo ./svc.sh uninstall && ./config.sh remove --token <TOKEN>`.

# 📁 Complete File Structure

```
informix-proxy/
│
├── 📄 pom.xml                                    # Maven build configuration (Java dependencies)
├── 📄 Dockerfile                                 # Docker image build instructions
├── 📄 docker-compose.prod.yml                    # Full stack (DB + Proxy + Monitoring)
├── 📄 README.md                                  # Main documentation
├── 📄 QUICKSTART.md                              # 5-minute setup guide
├── 📄 MONITORING.md                              # Monitoring stack documentation
├── 📄 WHY_GRPC.md                                # Why this beats REST
│
├── 📂 proto/                                     # Protocol Buffer definitions
│   └── 📄 informix.proto                         # ⭐ THE API CONTRACT - defines all RPC methods
│
├── 📂 src/main/java/com/informix/grpc/          # Java source code
│   └── 📄 InformixProxyServer.java              # ⭐ MAIN SERVICE - gRPC server implementation
│
├── 📂 lib/                                       # ❌ NOT NEEDED ANYMORE (Maven handles it)
│   └── (empty - Maven downloads JDBC driver)
│
├── 📂 scripts/                                   # Utility scripts
│   └── 📄 init-db.sh                            # Creates test database on startup
│
├── 📂 monitoring/                                # 📊 Observability stack
│   │
│   ├── 📄 prometheus.yml                         # Prometheus: What to scrape, how often
│   ├── 📄 alerts.yml                            # Alert rules: When to fire alerts
│   ├── 📄 alertmanager.yml                      # ❌ (with Slack)
│   ├── 📄 alertmanager-no-slack.yml             # ✅ USE THIS - Email/Teams/Discord/Webhooks
│   ├── 📄 loki-config.yml                       # Log aggregation config
│   ├── 📄 promtail-config.yml                   # Log shipping config
│   │
│   └── 📂 grafana/
│       ├── 📂 provisioning/
│       │   ├── 📂 datasources/
│       │   │   └── 📄 datasources.yml           # Connects Grafana to Prometheus/Loki
│       │   │
│       │   └── 📂 dashboards/
│       │       └── 📄 dashboards.yml            # Auto-loads dashboards
│       │
│       └── 📂 dashboards/
│           └── 📄 informix-proxy-dashboard.json  # Visual metrics dashboard
│
└── 📂 clients/                                   # Client libraries (your apps use these)
    │
    ├── 📂 nodejs/
    │   ├── 📄 package.json                       # ⭐ npm install uses this
    │   ├── 📄 informix-client.js                 # ⭐ Node.js client library
    │   └── 📄 test.js                           # Example usage
    │
    └── 📂 python/
        ├── 📄 requirements.txt                   # ⭐ pip install -r requirements.txt
        ├── 📄 informix_client.py                 # ⭐ Python client library
        └── 📄 example.py                         # Example usage


═══════════════════════════════════════════════════════════════════

🎯 FILES YOU MUST EDIT BEFORE USING
═══════════════════════════════════════════════════════════════════

1. monitoring/alertmanager-no-slack.yml
   └─ Line 11-15: Add your SMTP server details
      smtp_smarthost: 'smtp.gmail.com:587'
      smtp_from: 'your-email@gmail.com'
      smtp_auth_username: 'your-email@gmail.com'
      smtp_auth_password: 'your-app-password'

2. docker-compose.prod.yml (OPTIONAL - has defaults)
   └─ Line 9-12: Can customize database settings
   └─ Line 25-28: Can customize proxy JVM memory

═══════════════════════════════════════════════════════════════════

🚀 BUILD ORDER (Do this in sequence)
═══════════════════════════════════════════════════════════════════

Step 1: Create directory structure
$ mkdir -p informix-proxy/{proto,src/main/java/com/informix/grpc,scripts,monitoring/grafana/{provisioning/{datasources,dashboards},dashboards},clients/{nodejs,python}}

Step 2: Copy all files to their locations (see tree above)

Step 3: Build the project
$ cd informix-proxy
$ mvn clean package
   ↓
   Creates: target/informix-grpc-proxy-1.0.0.jar

Step 4: Start everything
$ docker-compose -f docker-compose.prod.yml up -d

Step 5: Wait 2 minutes (Informix needs time to initialize)

Step 6: Access services
   Grafana:      http://localhost:3000  (admin/admin)
   Prometheus:   http://localhost:9091
   Alertmanager: http://localhost:9093
   Proxy gRPC:   localhost:50051

═══════════════════════════════════════════════════════════════════

📦 WHAT GETS GENERATED (Auto-created, don't create manually)
═══════════════════════════════════════════════════════════════════

target/                                    # Maven build output
├── informix-grpc-proxy-1.0.0.jar         # Fat JAR with all dependencies
└── generated-sources/                     # Generated from .proto file
    └── protobuf/
        └── grpc-java/
            └── com/informix/grpc/
                ├── InformixServiceGrpc.java
                └── (other generated files)

clients/python/                            # Generated from .proto file
├── informix_pb2.py                       # Run: python -m grpc_tools.protoc ...
└── informix_pb2_grpc.py

═══════════════════════════════════════════════════════════════════

🗂️ DOCKER VOLUMES (Persistent data)
═══════════════════════════════════════════════════════════════════

Docker creates these automatically:
├── informix-data/          # Database files (persists between restarts)
├── prometheus-data/        # Metrics history (30 days retention)
├── grafana-data/           # Dashboards and settings
├── alertmanager-data/      # Alert state
└── loki-data/              # Log storage

To backup:
$ docker run --rm -v informix-data:/data -v $(pwd):/backup ubuntu tar czf /backup/informix-backup.tar.gz /data

To delete all data:
$ docker-compose -f docker-compose.prod.yml down -v

═══════════════════════════════════════════════════════════════════

🔍 WHERE TO FIND THINGS
═══════════════════════════════════════════════════════════════════

Need to:                        Look here:
────────────────────────────────────────────────────────────────────
Add a new RPC method            proto/informix.proto
Change connection pool size     src/.../InformixProxyServer.java (line 180)
Add custom metric               src/.../InformixProxyServer.java (anywhere)
Change alert thresholds         monitoring/alerts.yml
Add email recipient             monitoring/alertmanager-no-slack.yml
Create new dashboard            Grafana UI → Export JSON → monitoring/grafana/dashboards/
Change scrape interval          monitoring/prometheus.yml (line 2)
Modify database initialization  scripts/init-db.sh
Use in Node.js app              clients/nodejs/informix-client.js
Use in Python app               clients/python/informix_client.py

═══════════════════════════════════════════════════════════════════
```

## Quick Setup Script

Run this to create all directories:

```bash
#!/bin/bash
# setup-structure.sh

echo "Creating directory structure..."

mkdir -p informix-proxy/{proto,src/main/java/com/informix/grpc,scripts,lib,clients/{nodejs,python}}
mkdir -p informix-proxy/monitoring/grafana/{provisioning/{datasources,dashboards},dashboards}

echo "Directory structure created!"
echo ""
echo "Now copy files according to the tree above."
echo ""
echo "Quick checklist:"
echo "  ✓ proto/informix.proto"
echo "  ✓ src/main/java/com/informix/grpc/InformixProxyServer.java"
echo "  ✓ pom.xml"
echo "  ✓ docker-compose.prod.yml"
echo "  ✓ monitoring/*.yml files"
echo "  ✓ clients/*/files"
echo ""
```
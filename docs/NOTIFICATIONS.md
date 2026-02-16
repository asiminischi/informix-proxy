# Email & Webhook Notification Setup

Since you don't use Slack, here are your **best alternatives** for getting alerts.

## 📧 Option 1: Email Alerts (Recommended - Easiest)

### Setup with Gmail

1. **Create an App Password** (Gmail):
    - Go to: https://myaccount.google.com/apppasswords
    - Select "Mail" and "Other (Custom name)"
    - Name it "Informix Alerts"
    - Click "Generate"
    - Copy the 16-character password

2. **Edit `monitoring/alertmanager-no-slack.yml`**:

```yaml
global:
  smtp_smarthost: 'smtp.gmail.com:587'
  smtp_from: 'your-email@gmail.com'
  smtp_auth_username: 'your-email@gmail.com'
  smtp_auth_password: 'abcd efgh ijkl mnop'  # ← Paste the app password here
  smtp_require_tls: true
```

3. **Update email addresses**:

```yaml
receivers:
  - name: 'email-default'
    email_configs:
      - to: 'team@yourdomain.com'  # ← Your team email
```

**That's it!** You'll now get HTML-formatted emails like this:

```
From: informix-alerts@gmail.com
To: team@yourdomain.com
Subject: [CRITICAL] Informix Alert: ConnectionPoolExhausted

🚨 CRITICAL ALERT

Alert: ConnectionPoolExhausted
Severity: critical
Component: connection_pool
Summary: Connection pool exhausted
Description: Pool main-pool is at 97% capacity. New connections will be blocked!

Dashboard: http://localhost:3000/d/informix-proxy/connection-pool
```

### Other Email Providers

**Office 365:**
```yaml
smtp_smarthost: 'smtp.office365.com:587'
smtp_auth_username: 'you@company.com'
smtp_auth_password: 'your-password'
```

**SendGrid:**
```yaml
smtp_smarthost: 'smtp.sendgrid.net:587'
smtp_auth_username: 'apikey'
smtp_auth_password: 'SG.your-sendgrid-api-key'
```

**AWS SES:**
```yaml
smtp_smarthost: 'email-smtp.us-east-1.amazonaws.com:587'
smtp_auth_username: 'YOUR_SMTP_USERNAME'
smtp_auth_password: 'YOUR_SMTP_PASSWORD'
```

---

## 💬 Option 2: Microsoft Teams

1. **Create Incoming Webhook in Teams**:
    - Open your Teams channel
    - Click ⋯ → Connectors → Incoming Webhook
    - Name it "Informix Alerts"
    - Copy the webhook URL

2. **Edit `monitoring/alertmanager-no-slack.yml`**:

```yaml
receivers:
  - name: 'teams-notifications'
    webhook_configs:
      - url: 'https://outlook.office.com/webhook/xxxxx/IncomingWebhook/yyyyy'
        send_resolved: true
```

3. **Create a Teams webhook receiver** (simple Node.js app):

```javascript
// teams-webhook.js
const express = require('express');
const axios = require('axios');

const app = express();
app.use(express.json());

const TEAMS_WEBHOOK = 'https://outlook.office.com/webhook/xxxxx';

app.post('/alerts', async (req, res) => {
    const alerts = req.body.alerts;
    
    for (const alert of alerts) {
        const card = {
            "@type": "MessageCard",
            "themeColor": alert.labels.severity === 'critical' ? "FF0000" : "FFA500",
            "summary": alert.annotations.summary,
            "sections": [{
                "activityTitle": alert.labels.alertname,
                "activitySubtitle": alert.annotations.summary,
                "facts": [
                    { "name": "Severity", "value": alert.labels.severity },
                    { "name": "Component", "value": alert.labels.component },
                    { "name": "Description", "value": alert.annotations.description }
                ],
                "markdown": true
            }],
            "potentialAction": [{
                "@type": "OpenUri",
                "name": "View Dashboard",
                "targets": [{
                    "os": "default",
                    "uri": alert.annotations.dashboard
                }]
            }]
        };
        
        await axios.post(TEAMS_WEBHOOK, card);
    }
    
    res.json({ status: 'ok' });
});

app.listen(8080, () => console.log('Teams webhook receiver running on :8080'));
```

Run it:
```bash
npm install express axios
node teams-webhook.js
```

---

## 🎮 Option 3: Discord

1. **Create Webhook in Discord**:
    - Right-click your channel → Edit Channel
    - Integrations → Webhooks → New Webhook
    - Name it "Informix Alerts"
    - Copy Webhook URL

2. **Edit `monitoring/alertmanager-no-slack.yml`**:

```yaml
receivers:
  - name: 'discord-notifications'
    webhook_configs:
      - url: 'https://discord.com/api/webhooks/123456/abcdef'
        send_resolved: true
```

3. **Create Discord webhook formatter**:

```javascript
// discord-webhook.js
const express = require('express');
const axios = require('axios');

const app = express();
app.use(express.json());

const DISCORD_WEBHOOK = 'https://discord.com/api/webhooks/123456/abcdef';

app.post('/alerts', async (req, res) => {
    const alerts = req.body.alerts;
    
    for (const alert of alerts) {
        const color = alert.labels.severity === 'critical' ? 16711680 : 16753920;
        
        const embed = {
            title: `🚨 ${alert.labels.alertname}`,
            description: alert.annotations.summary,
            color: color,
            fields: [
                { name: "Severity", value: alert.labels.severity, inline: true },
                { name: "Component", value: alert.labels.component, inline: true },
                { name: "Description", value: alert.annotations.description }
            ],
            timestamp: new Date().toISOString()
        };
        
        if (alert.annotations.dashboard) {
            embed.fields.push({
                name: "Dashboard",
                value: `[View Dashboard](${alert.annotations.dashboard})`
            });
        }
        
        await axios.post(DISCORD_WEBHOOK, {
            embeds: [embed]
        });
    }
    
    res.json({ status: 'ok' });
});

app.listen(8080, () => console.log('Discord webhook receiver on :8080'));
```

---

## 🔔 Option 4: Generic Webhook (Custom Integration)

Create your own alert receiver for ANY platform:

```python
# webhook-receiver.py
from flask import Flask, request, jsonify
import requests

app = Flask(__name__)

@app.route('/alerts', methods=['POST'])
def receive_alerts():
    data = request.json
    
    for alert in data['alerts']:
        severity = alert['labels'].get('severity', 'info')
        alertname = alert['labels'].get('alertname', 'Unknown')
        summary = alert['annotations'].get('summary', '')
        description = alert['annotations'].get('description', '')
        
        # Send to your platform
        # Examples:
        # - Post to your internal API
        # - Insert into database
        # - Trigger SMS via Twilio
        # - Send push notification
        
        print(f"[{severity.upper()}] {alertname}: {summary}")
        print(f"  {description}")
        
        # Example: Send SMS via Twilio
        # send_sms(f"ALERT: {alertname} - {summary}")
        
        # Example: Log to database
        # db.alerts.insert({
        #     'alertname': alertname,
        #     'severity': severity,
        #     'summary': summary,
        #     'description': description,
        #     'timestamp': datetime.now()
        # })
    
    return jsonify({'status': 'ok'})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
```

Configure Alertmanager:
```yaml
receivers:
  - name: 'custom-webhook'
    webhook_configs:
      - url: 'http://your-server:8080/alerts'
        send_resolved: true
```

---

## 📱 Option 5: SMS via Twilio

```python
# sms-alerts.py
from flask import Flask, request
from twilio.rest import Client

app = Flask(__name__)

# Twilio credentials
TWILIO_ACCOUNT_SID = 'your_account_sid'
TWILIO_AUTH_TOKEN = 'your_auth_token'
TWILIO_FROM = '+1234567890'
ALERT_TO = '+1987654321'

client = Client(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN)

@app.route('/alerts', methods=['POST'])
def sms_alert():
    data = request.json
    
    for alert in data['alerts']:
        if alert['labels'].get('severity') == 'critical':
            message = f"🚨 CRITICAL: {alert['labels']['alertname']}\n"
            message += f"{alert['annotations']['summary']}"
            
            client.messages.create(
                body=message,
                from_=TWILIO_FROM,
                to=ALERT_TO
            )
    
    return {'status': 'ok'}

app.run(host='0.0.0.0', port=8080)
```

---

## ⚙️ Testing Your Setup

After configuring, test with:

```bash
# Send test alert
curl -X POST http://localhost:9093/api/v1/alerts \
  -H 'Content-Type: application/json' \
  -d '[{
    "labels": {
      "alertname": "TestAlert",
      "severity": "warning"
    },
    "annotations": {
      "summary": "This is a test alert",
      "description": "Testing the notification pipeline"
    }
  }]'
```

You should receive a notification within 30 seconds.

---

## 🎯 Recommended Setup for Your Use Case

Based on typical enterprise needs without Slack:

**Primary:** Email (easiest, built-in)
**Secondary:** Teams or Discord (if your team uses them)
**Critical-only:** SMS via Twilio (for on-call)

Configuration:
```yaml
route:
  receiver: 'email-default'
  routes:
    - match:
        severity: critical
      receiver: 'critical-sms'
      continue: true
    
    - match:
        component: database
      receiver: 'database-team-email'
```

This gives you:
- ✅ All alerts via email (HTML formatted)
- ✅ Critical alerts trigger SMS
- ✅ Database team gets their own alerts
- ✅ No Slack needed

---

## 📋 Quick Reference

| Method | Setup Time | Reliability | Cost | Best For |
|--------|------------|-------------|------|----------|
| Email | 5 min | ⭐⭐⭐⭐⭐ | Free | Default choice |
| Teams | 10 min | ⭐⭐⭐⭐ | Free | Microsoft shops |
| Discord | 10 min | ⭐⭐⭐⭐ | Free | Developer teams |
| SMS | 20 min | ⭐⭐⭐⭐⭐ | $0.01/msg | Critical only |
| Custom | 30+ min | ⭐⭐⭐ | Varies | Special needs |

**Start with email. Add others as needed.**
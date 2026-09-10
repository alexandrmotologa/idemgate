import urllib.request
import json
import time

base_url = "http://localhost:8080"

def send_request(path, idempotency_key, tenant_id, api_key, body):
    url = f"{base_url}{path}"
    data = json.dumps(body).encode('utf-8')
    headers = {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotency_key,
        "X-Tenant-ID": tenant_id,
        "X-API-Key": api_key
    }
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode('utf-8')
            replayed = resp.headers.get("Idempotent-Replayed", "false")
            print(f"[OK] Status: {resp.status} | Replayed: {replayed} | Key: {idempotency_key}")
            return resp.status, content
    except urllib.error.HTTPError as e:
        content = e.read().decode('utf-8')
        print(f"[HTTP {e.code}] Key: {idempotency_key} | Response: {content}")
        return e.code, content

print("--- 1. Reset invocations counter ---")
try:
    req = urllib.request.Request(f"{base_url}/upstream-mock/invocations/reset", data=b"", method="POST")
    urllib.request.urlopen(req)
except Exception as e:
    print(e)

print("--- 2. Fresh order 1 ---")
send_request("/api/v1/orders", "ord-9821-acme", "acme-corp", "free", {
    "orderId": "ORD-9821",
    "amount": 249.99,
    "currency": "USD",
    "customer": "Acme Corp"
})

print("--- 3. Replay order 1 (Cached / Idempotent) ---")
send_request("/api/v1/orders", "ord-9821-acme", "acme-corp", "free", {
    "orderId": "ORD-9821",
    "amount": 249.99,
    "currency": "USD",
    "customer": "Acme Corp"
})

print("--- 4. Payload mismatch (422) ---")
send_request("/api/v1/orders", "ord-9821-acme", "acme-corp", "free", {
    "orderId": "ORD-9821",
    "amount": 999.99,
    "currency": "USD",
    "customer": "TAMPERED"
})

print("--- 5. Payment 1 (Pro Tier) ---")
send_request("/api/v1/payments", "pay-3301-fintech", "fintech-ltd", "pro", {
    "paymentId": "PAY-3301",
    "amount": 1500.00,
    "currency": "EUR",
    "method": "SEPA_INSTANT"
})

print("--- 6. Replay payment 1 ---")
send_request("/api/v1/payments", "pay-3301-fintech", "fintech-ltd", "pro", {
    "paymentId": "PAY-3301",
    "amount": 1500.00,
    "currency": "EUR",
    "method": "SEPA_INSTANT"
})

print("--- 7. Inventory reservation (Enterprise Tier) ---")
send_request("/api/v1/inventory/reserve", "inv-7704-logistics", "logistics-hub", "enterprise", {
    "warehouse": "BER-01",
    "sku": "SKU-LOG-99",
    "quantity": 45
})

print("--- 8. Replay inventory reservation ---")
send_request("/api/v1/inventory/reserve", "inv-7704-logistics", "logistics-hub", "enterprise", {
    "warehouse": "BER-01",
    "sku": "SKU-LOG-99",
    "quantity": 45
})

print("--- 9. Additional diverse keys ---")
send_request("/api/v1/subscriptions", "sub-0042-saas", "saas-co", "pro", {
    "plan": "premium-annual",
    "seats": 50
})
send_request("/api/v1/webhooks/dispatch", "wh-9912-cloud", "cloud-infra", "enterprise", {
    "event": "deployment.completed",
    "target": "https://hooks.example.com/deploy"
})

print("Done populating traffic.")

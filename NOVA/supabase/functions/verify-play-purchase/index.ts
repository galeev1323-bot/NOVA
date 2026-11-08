// Edge Function: серверная проверка подписки Google Play. НЕ ПРОТЕСТИРОВАНО без вашего Play-аккаунта.
// Secrets: GOOGLE_SERVICE_ACCOUNT_JSON, PLAY_PACKAGE_NAME (supabase secrets set ...)
// SUPABASE_URL и SUPABASE_SERVICE_ROLE_KEY доступны функции автоматически (ключ остаётся только на сервере).
import { createClient } from "npm:@supabase/supabase-js@2";
import { google } from "npm:googleapis@144";

Deno.serve(async (req) => {
  const auth = req.headers.get("Authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL")!;
  const userClient = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
  const { data: { user } } = await userClient.auth.getUser();
  if (!user) return new Response("unauthorized", { status: 401 });

  const { purchaseToken, productId } = await req.json();
  const jwt = new google.auth.GoogleAuth({
    credentials: JSON.parse(Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON")!),
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });
  const api = google.androidpublisher({ version: "v3", auth: jwt });
  const res = await api.purchases.subscriptionsv2.get({
    packageName: Deno.env.get("PLAY_PACKAGE_NAME")!, token: purchaseToken,
  });
  const state = res.data.subscriptionState;
  const expiry = res.data.lineItems?.[0]?.expiryTime;
  const active = state === "SUBSCRIPTION_STATE_ACTIVE" && expiry && new Date(expiry) > new Date();
  if (!active) return new Response("not active", { status: 402 });

  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  await admin.from("subscriptions").upsert({
    user_id: user.id, source: "google_play", product_id: productId,
    purchase_token: purchaseToken, status: "active", expires_at: expiry,
  });
  await admin.from("profiles").update({ is_premium: true, premium_until: expiry }).eq("id", user.id);
  return new Response("ok");
});

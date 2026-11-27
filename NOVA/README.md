# NOVA Messenger (Android, Kotlin + Jetpack Compose + Supabase)

⚠️ Проект ещё требует проверки сборки. Не считайте функции, требующие Supabase, готовыми к работе до применения SQL и тестирования.

## 1. Backend (Supabase)
1. Создайте проект на https://supabase.com.
2. SQL Editor → вставьте и выполните `supabase/schema.sql`.
3. Затем примените миграции из `supabase/migrations` по порядку:
   - `20261108_stars_owner.sql`
   - `20261108_owner_premium.sql`
   - `20261108_owner_badge.sql`
   - `20261123_stars_transfers_gifts.sql`
   - `20261123_chess_games.sql`
   - `20261127_stories_channels.sql` (Stories/Channels)
4. Authentication → Providers настройте Email и при необходимости Phone/SMS.
5. Project Settings → API: URL и anon public key.
6. Для реального Premium нужна серверная проверка покупки и настройки Google Play Console.

## 2. Настройка приложения
Скопируйте `local.properties.example` → `local.properties`, впишите `supabase.url` и `supabase.anon.key`. Секретный service_role в приложение не добавляйте.

## 3. Сборка APK
Android Studio: откройте папку проекта, дождитесь Gradle Sync, затем Build → Build APK(s). APK debug появится в `app/build/outputs/apk/debug/app-debug.apk`. Сборка ещё должна быть проверена GitHub Actions.

## Что подготовлено в ветке 2.3.0
- Stars, подарки, переводы и шахматные функции (требуют миграций и тестирования).
- Вкладка «Медиа» с экраном Stories/Channels.
- Локальные текстовые черновики историй и каналов: они не публикуются, серверного сохранения и загрузки медиа пока нет.
- Профиль, настройки, Premium и существующие функции чатов из предыдущей версии.

## Ограничения
- E2E-шифрования нет; сообщения читаемы на сервере.
- Push при закрытом приложении требует FCM и серверной отправки.
- Storage и политика доступа требуют проверки/настройки.
- Privacy и блокировки требуют серверной проверки политик.
- Billing требует Play Console и тестирования.
- Автоматических тестов пока нет.
- Stories/Channels пока не подключены к Supabase и не публикуются.
- NOVA AI добавлен как демонстрационный экран без подключения модели.
- Планшетный двухпанельный режим пока не реализован.

# راهنمای فارسی IBM MQ Spring JmsTemplate Lab

این پروژه یک برنامهٔ مستقل Java 21 و Spring Boot برای ارسال و دریافت **صریح** پیام با `JmsTemplate` است. دریافت با فراخوانی `receive` انجام می‌شود؛ `@JmsListener` یا listener container نداریم. دو زمان‌بند قابل تنظیم، پیام‌های ورودی و خروجی را پردازش می‌کنند. تنظیمات صف از سرور HTTP mock دریافت می‌شود و در زمان اجرای برنامه تغییر می‌کند.

## اجزای آزمایش

| جزء          | وظیفه                                               |
|--------------|-----------------------------------------------------|
| IBM MQ       | Queue Manager به نام `QM1` و صف‌های آزمایش          |
| Mock         | ارائه و تغییر `QueueSettings` و شبیه‌سازی خطای HTTP |
| Spring agent | دریافت تنظیمات، زمان‌بندی و ارسال/دریافت دسته‌ای    |
| CLI و shell  | آزمون تک‌پیامی، rollback و مشاهدهٔ وضعیت            |
| پوشهٔ `data` | journal دریافت و outbox ارسال روی میزبان            |

`inward` یعنی دریافت از `DEV.LAB.IN` و ثبت در journal محلی. `outward` یعنی ارسال کارهای آمادهٔ outbox محلی به `DEV.LAB.OUT`. برنامه، پیام IN را خودکار به OUT منتقل نمی‌کند. OUT نیز مصرف‌کنندهٔ پیش‌فرض ندارد؛ افزایش عمق آن پس از ارسال طبیعی است.

## پیش‌نیازها

JDK **21**، Docker و Compose v2 با پشتیبانی `--wait`، Bash، OpenSSL و curl لازم است. Maven Wrapper نسخهٔ Maven را دریافت می‌کند. Python 3.11 یا بالاتر فقط برای اجرای mock روی میزبان و تست Python لازم است؛ mock داخل Docker از Python 3.13 استفاده می‌کند. دستورات را از ریشهٔ پروژه اجرا کنید. در Windows از WSL استفاده کنید. حافظهٔ مناسب برای Docker، حداقل حدود ۴ گیگابایت در نظر بگیرید.

تصویر IBM MQ این آزمایش `linux/amd64` است. روی ARM، اجرای Docker به شبیه‌سازی معماری و منابع بیشتر نیاز دارد. این تنظیمات برای محیط توسعه با IBM MQ Developer هستند؛ شروع سرویس با فایل Compose شامل پذیرش مجوز تصویر IBM است.

## راه‌اندازی از ابتدا

```bash
./scripts/init-lab.sh
./mvnw -B -ntp verify
docker compose up -d --wait --wait-timeout 360 mq mock
docker compose ps
./scripts/lab.sh demo
```

اسکریپت init دو رمز مستقل و تصادفی، بدون newline، می‌سازد:

- `.secrets/mqAppPassword` برای کاربر برنامه `app`؛
- `.secrets/mqAdminPassword` برای کاربر مدیریتی `admin`.

این فایل‌ها به سرویس MQ به صورت secret متصل می‌شوند. اسکریپت فایل موجود را بازنویسی نمی‌کند. پوشهٔ `.secrets` در Git نادیده گرفته می‌شود؛ آن را منتشر نکنید. پس از ساخت volume و ایجاد کاربران، تغییر دلخواه فایل رمز به معنی به‌روزرسانی خودکار رمز سرور نیست. رمزهای موجود را حفظ کنید؛ برای شروع کاملاً پاک، ابتدا پیامد حذف volume را بررسی کنید و بخش زیرساخت را بخوانید.

خروجی `demo` باید سه نتیجهٔ `PASS` داشته باشد: ارسال rollback شده دیده نمی‌شود؛ خطای handler باعث rollback و تحویل مجدد می‌شود؛ دریافت موفق پیام را از صف حذف می‌کند. این آزمون فقط پیام‌های خودش را در صف TEST، با Message ID دقیق، مصرف می‌کند.

| تنظیم            | مقدار پیش‌فرض                          |
|------------------|----------------------------------------|
| Host / MQ port   | `localhost:1414`                       |
| Queue Manager    | `QM1`                                  |
| Channel          | `DEV.APP.SVRCONN`                      |
| کاربر برنامه     | `app`                                  |
| صف ورودی / خروجی | `DEV.LAB.IN` / `DEV.LAB.OUT`           |
| صف آزمون         | `DEV.LAB.TEST`                         |
| Mock روی میزبان  | `http://localhost:8088/queue-settings` |

فایل `docker/mq/20-lab.mqsc` صف‌های IN، OUT، TEST و دو صف رزروشدهٔ integration test را ایجاد می‌کند. پیام‌ها persistent هستند و حداکثر متن/پاکت هر پیام در برنامه ۱ MiB است.

## IBM MQ Web Console

۱. پس از healthy شدن MQ، در مرورگر باز کنید:

```text
https://localhost:9443/ibmmq/console
```

۲. گواهی HTTPS محیط توسعه self-signed است. در محیط آزمایش، پس از بررسی اینکه آدرس همان localhost مورد نظر است، استثنای مرورگر را برای آن بپذیرید. برای محیط عملیاتی باید گواهی معتبر و TLS مناسب داشته باشید.

۳. با کاربر `admin` وارد شوید. رمز همان محتوای فایل زیر است:

```bash
cat .secrets/mqAdminPassword
printf '\n'
```

این دستور فقط برای مشاهدهٔ محلی رمز است؛ آن را در لاگ CI، عکس عمومی یا repository قرار ندهید. رمز admin با رمز app یکی نیست. برنامهٔ Java از رمز app استفاده می‌کند، نه حساب مدیریتی.

۴. Queue Manager `QM1` را انتخاب کنید. در بخش مدیریت/Queues صف‌های `DEV.LAB.IN`، `DEV.LAB.OUT` و `DEV.LAB.TEST` را پیدا کنید. عنوان دقیق منوها ممکن است با نسخهٔ کنسول تفاوت داشته باشد.

۵. مقدار **Current queue depth** را ببینید و پس از هر آزمایش Refresh کنید. با Browse messages می‌توانید پیام‌ها را مشاهده کنید. Browse پیام را حذف نمی‌کند. از Clear/Purge صف برای آزمایش معمول استفاده نکنید؛ دریافت موفق CLI یا agent پیام را مصرف می‌کند.

۶. وقتی agent فعال است ممکن است پیام ورودی بلافاصله مصرف شود؛ برای مشاهدهٔ IN ابتدا `enabled=false` کنید. پس از ارسال خروجی، چون مصرف‌کنندهٔ OUT نداریم، عمق OUT افزایش پیدا می‌کند.

۷. ارسال با rollback نباید پیام قابل مشاهده باقی بگذارد. دریافت rollback شده پیام را دوباره قابل دریافت می‌کند. کنسول فقط کار تکمیل‌شده را نشان می‌دهد و باید آن را Refresh کنید.

دستور `browse` یا `depth` در CLI، شمارش محدود با JMS QueueBrowser است؛ معادل دقیق مقدار مدیریتی CURDEPTH نیست. برای عمق از کنسول یا دستور زیر استفاده کنید:

```bash
printf "DISPLAY QLOCAL('DEV.LAB.*') CURDEPTH\n" | docker compose exec -T mq runmqsc QM1
```

خطای باز شدن کنسول را با `docker compose ps` و `docker compose logs --tail=100 mq` بررسی کنید. پورت 9443 باید آزاد باشد و سرویس هنوز در حال startup نباشد. توضیحات Kubernetes و port-forward در [infrastructure.md](infrastructure.md) آمده است.

## ارسال و دریافت یک پیام

```bash
./scripts/lab.sh send --lab.queue=in --lab.message='سلام از Spring JmsTemplate'
./scripts/lab.sh receive --lab.queue=in
```

`send`، Message ID و وضعیت commit را برمی‌گرداند. اگر می‌خواهید فقط پیام خودتان را دریافت کنید، ID واقعی را جایگزین کنید:

```bash
./scripts/lab.sh receive --lab.queue=in --lab.message-id=ID:YOUR_48_HEX_DIGITS
```

خروجی داخل handler دریافت، پیش از commit چاپ می‌شود؛ موفقیت کامل عملیات همچنان به commit بعدی بستگی دارد. این رفتار در خطای شبکه مهم است. فرمان‌های دستی تنظیمات `lab.mq.*` روی میزبان را استفاده می‌کنند و از `enabled` یا نام صف در mock پیروی نمی‌کنند.

آزمون rollback ارسال و دریافت:

```bash
./scripts/lab.sh send --lab.queue=test --lab.message='ارسال لغوشده' --lab.outcome=rollback
./scripts/lab.sh send --lab.queue=test --lab.message='دریافت و بازگردانی'
./scripts/lab.sh receive --lab.queue=test --lab.outcome=rollback
./scripts/lab.sh receive --lab.queue=test
```

`--lab.outcome=backout` نیز معادل rollback است. در صف مشترک از `--lab.message-id` استفاده کنید تا پیام دیگری دریافت نشود.

## shell تعاملی

```bash
./scripts/lab.sh shell
```

```text
put in Hello
get-rollback in
get in
put-rollback test Discard
browse test
settings
status
quit
```

هر put/get یک تراکنش مستقل و تکمیل‌شده دارد؛ با برگشت prompt، کار معلقی برای commit یا backout بعدی باقی نمی‌ماند. shell یک اتصال فیزیکی را میان فرمان‌ها نگه می‌دارد؛ در خطای ارتباطی از آن خارج شوید و دوباره باز کنید. پس از نتیجهٔ نامعلوم ارسال، scope عمداً برای عملیات بعدی مسدود می‌شود. shell دستورات begin/commit برای باز نگه داشتن تراکنش میان promptها ندارد.

## اجرای زمان‌بند روی میزبان

ابتدا چند کار خروجی بسازید:

```bash
./scripts/settings.sh get
./scripts/lab.sh enqueue --lab.count=13 --lab.message='کار خروجی زمان‌بندی‌شده'
./scripts/lab.sh status
./scripts/lab.sh agent
```

agent در ترمینال اول بماند. در ترمینال دوم:

```bash
./scripts/lab.sh send --lab.queue=in --lab.message='پیام ورودی برای agent'
./scripts/settings.sh patch '{"inward-batch-count":2,"outward-batch-count":3,"inward-interval-ms":4000,"outward-interval-ms":5000}'
./scripts/lab.sh status
```

با تنظیم بالا، هر نوبت inward حداکثر ۲ پیام دریافت می‌کند و هر نوبت outward حداکثر ۳ کار آماده می‌فرستد. اگر صف زودتر خالی شود یا کاری آماده نباشد، نوبت زودتر تمام می‌شود. هر جهت scope اتصال تازه‌ای می‌سازد و داخل آن از یک اتصال فیزیکی برای تراکنش‌های همان نوبت استفاده می‌کند.

فاصله‌ها، **پس از پایان نوبت** محاسبه می‌شوند. دو جهت روی یک worker به صورت متوالی اجرا می‌شوند؛ زمان اجرای یکی می‌تواند شروع دیگری را عقب بیندازد. دریافت تنظیمات worker جدا دارد و از خرابی MQ متوقف نمی‌شود. با Ctrl+C برنامه را متوقف کنید.

برای اجرای قابل مشاهده و محدود، بدون زمان‌بند پس‌زمینه:

```bash
./scripts/lab.sh once
```

این فرمان تنظیمات mock را می‌خواند و یک نوبت دریافت و یک نوبت ارسال انجام می‌دهد. خطای MQ را در log ثبت می‌کند؛ خروج موفق فرایند، قرارداد health-check اتصال نیست. سناریوی دقیق ۵+۵+۳ پیام در [exercises.md](exercises.md) آمده است.

## تغییر زنده و بازگشت از خطا

```bash
# توقف شروع پیام‌های تازه در هر دو جهت:
./scripts/settings.sh patch '{"enabled":false}'
# شروع مجدد:
./scripts/settings.sh patch '{"enabled":true}'
# endpoint خراب؛ poll تنظیمات همچنان کار می‌کند:
./scripts/settings.sh patch '{"port":1}'
# اصلاح تنظیمات بدون restart برنامه:
./scripts/settings.sh patch '{"port":1414}'
```

PATCH خودش `revision` را افزایش می‌دهد. تغییر نام صف، batch count، interval، host یا port در نوبت بعدی اعمال می‌شود. قبل از هر پیام جدید، نسل تنظیمات، فعال بودن و تازگی آن بررسی می‌شود. تراکنش در حال اجرا با تغییر تنظیمات به زور لغو نمی‌شود؛ می‌تواند با تنظیمات قبلی تمام شود. قطع شبکه در میانهٔ commit ممکن است نتیجهٔ نامعلوم داشته باشد.

آزمایش قطع واقعی MQ، در حالی که agent اجراست:

```bash
docker compose stop mq
# خطا و retry را در log agent ببینید.
docker compose start mq
# پس از آماده شدن MQ، کارهای آماده دوباره اجرا می‌شوند.
```

آزمایش خرابی سرویس تنظیمات:

```bash
./scripts/settings.sh fail
# بیش از ۱۵ ثانیه صبر کنید: تنظیمات معتبر قبلی منقضی و کار جدید متوقف می‌شود.
./scripts/settings.sh recover
```

دریافت معتبر، حتی با همان revision و همان محتوا، عمر تنظیمات را تمدید می‌کند. محتوا با revision قبلی تغییر کند یا revision عقب برود، رد می‌شود. هنگام شکست HTTP/validation، تنظیمات قبلی فقط تا `lab.settings-max-age-ms` معتبرند. برنامه بدون تنظیم معتبر اولیه هیچ کار MQ شروع نمی‌کند. اصلاح پاسخ معتبر و `enabled=true` باعث شروع مجدد خودکار می‌شود.

`failure-retry-ms` فاصلهٔ بعد از برگشت خطای MQ است؛ timeout اتصال TCP نیست. فراخوانی provider ممکن است طولانی شود. دریافت تنظیمات مستقل می‌ماند، اما دو جهت MQ منتظر worker مشترک خواهند بود.

## Spring چه چیزی را خودکار مدیریت می‌کند؟

این بخش برای درک commit/backout مهم است:

- صرف استفاده از `JmsTemplate` به معنی تراکنش درست برای کل پردازش یا کل batch نیست. مرز تراکنش را باید تعیین کرد.
- این پروژه `JmsTransactionManager` و `TransactionTemplate` را با همان `ConnectionFactory` مورد استفادهٔ JmsTemplate پیکربندی کرده است.
- دریافت **به همراه handler** داخل یک تراکنش Spring است. موفقیت handler باعث commit و استثنا باعث rollback می‌شود.
- برنامه درخواست rollback آزمایشی را با `status.setRollbackOnly()` اعلام می‌کند؛ خود Spring عملیات JMS rollback را اجرا می‌کند.
- ارسال نیز داخل تراکنش است و نتیجهٔ موفق پس از commit به caller می‌رسد.
- **هر پیام یک تراکنش دارد**؛ batch count فقط سقف کار آن نوبت است. اگر پیام چهارم خطا دهد، commit پیام‌های اول تا سوم برگردانده نمی‌شود.
- برنامه `Session.commit()`، `Session.rollback()` یا backout بومی MQ را دستی فراخوانی نمی‌کند. انتخاب شروع/پایان scope اتصال همچنان جزء طراحی برنامه است و منابع توسط Spring بسته می‌شوند.

اگر نیاز کسب‌وکار، commit/rollback کل batch به صورت یکجا باشد، باید کل حلقه در یک TransactionTemplate قرار گیرد. آن مدل در این پروژه پیاده نشده و پیامدهایی مانند طولانی شدن قفل‌ها و بازگشت همهٔ پیام‌های batch دارد. نباید داخل تراکنش تحت مدیریت Spring دوباره commit دستی انجام داد. طرح و جزئیات در [architecture.md](architecture.md) توضیح داده شده است.

## پیام‌های خروجی با نتیجهٔ نامعلوم

ساختار محلی:

```text
 data/inward                  journal دریافت بر اساس Message ID
 data/outward/ready           آمادهٔ ارسال
 data/outward/in-flight       در حال ارسال
 data/outward/sent            commit ارسال تأیید شده
 data/outward/uncertain       نیازمند بررسی نتیجهٔ ارسال
```

اگر قبل از ارسال اتصال برقرار نشود یا rollback تأیید شود، کار به ready برمی‌گردد و دوباره تلاش می‌شود. اگر ارسال انجام شده ولی پاسخ commit از دست برود، ممکن است پیام در MQ موجود باشد. برنامه آن را به uncertain منتقل می‌کند و خودکار دوباره نمی‌فرستد. کارهای ready پس از بازگشت ارتباط ادامه می‌یابند؛ uncertain نیاز به تصمیم شما دارد.

هر پیام زمان‌بندی‌شده، پاکت JSON با `work-id` پایدار و `payload` دارد. برای بررسی uncertain، ابتدا agent را pause کنید و نتیجه را در MQ یا سوابق مصرف‌کننده با work-id بررسی کنید. CLI browse فقط شمارش دارد، جستجوی payload UUID انجام نمی‌دهد. پس از تصمیم آگاهانه می‌توانید دوباره آماده کنید:

```bash
./scripts/lab.sh requeue --lab.work-id=UUID_REAL_WORK_ITEM
```

پس از crash، in-flightهای باقیمانده در شروع agent بعدی به uncertain می‌روند. journal/outbox فایل و MQ یک تراکنش مشترک ندارند؛ این پروژه تضمین exactly-once سراسری یا دوام در قطع برق نمی‌دهد. journal دریافت، تحویل تکراری با همان Message ID و متن را دوباره ثبت نمی‌کند. این رفتار جایگزین inbox/outbox پایدار متناسب با کسب‌وکار نیست.

متن ورودی با پیشوند `FAIL:` عمداً handler را به خطا می‌اندازد؛ دریافت rollback و با فاصله دوباره امتحان می‌شود. انتقال خودکار poison message به backout queue در این پروژه وجود ندارد. برای رفع پیام آزمایشی، agent را pause و آن Message ID را با CLI مصرف کنید.

## اجرای agent داخل Docker

spool میزبان `data/` و volume کانتینر `agent-data` مستقل‌اند. برای کانتینر، enqueue/status را هم از همان کانتینر اجرا کنید:

```bash
docker compose --profile agent build agent
docker compose --profile agent run --rm agent --lab.command=enqueue --lab.count=13 --lab.message='Container batch'
docker compose --profile agent up -d agent
docker compose logs -f agent
# در ترمینال دیگر:
docker compose --profile agent run --rm agent --lab.command=status
```

در شبکهٔ Compose، agent از `http://mock:8080/queue-settings/container` می‌خواند و نام میزبان MQ برابر `mq` است. این endpoint فقط localhost/127.0.0.1 را به mq تبدیل می‌کند؛ host غلط آزمایشی را پنهان نمی‌کند. برای خاموش کردن agent:

```bash
docker compose --profile agent stop agent
```

## تست، وابستگی‌ها و راهنماها

```bash
./mvnw -B -ntp verify
python3 -m unittest discover -s mock/tests -v
./mvnw -B -ntp -Pmq-it verify
./scripts/check-dependencies.sh
```

گزینهٔ `mq-it` به MQ واقعی آماده، رمزها و صف‌های فایل MQSC نیاز دارد. صف‌های `DEV.LAB.IT.IN/OUT` مخصوص تست باید خالی و بدون agent دیگر باشند. این تست‌ها صف مشترک را purge نمی‌کنند. تست‌های واحد و mock بدون MQ واقعی اجرا می‌شوند. وضعیت دقیق بررسی‌های انجام‌شده هنگام آماده‌سازی سورس در [verification.md](verification.md) ثبت شده است.

نسخه‌های Logback و Bouncy Castle در POM صریح تعیین شده‌اند. دستور check-dependencies نسخهٔ واقعی dependency tree را نشان می‌دهد؛ جایگزین اسکن CVE نیست. پس از تغییر نسخه‌ها، اسکن IDE/Maven خود را دوباره اجرا کنید.

برای نصب Kubernetes، مشاهدهٔ MQSC، مدیریت volume، رمزها و شروع GitHub از [infrastructure.md](infrastructure.md) استفاده کنید. قرارداد تنظیمات و overrideهای محلی در [queue-settings.md](queue-settings.md) و سناریوهای عملی در [exercises.md](exercises.md) آمده‌اند.

package ir.diban.android

/**
 * Persian texts for the setup wizard and help pages.
 *
 * Writing rules (same as the desktop app, they keep mixed Persian/English readable):
 *  - every sentence is pure Persian with no trailing punctuation;
 *  - English names of buttons in Google's / Android's screens go in `chips`, shown left-to-right
 *    under the sentence.  Several chips in a row mean "then, in this order".
 */
object WizardSteps {
    enum class Action { OPEN_SCRIPT, COPY_SCRIPT, PASTE_ID }

    class Item(val text: String, val chips: List<String>? = null)

    class Step(
        val title: String,
        val items: List<Item>,
        val action: Pair<String, Action>? = null,
        val entry: Boolean = false,
        val tip: String? = null,
    )

    /** hl=en makes Google show English button names, matching the chips. */
    const val OPEN_URL = "https://script.google.com/home/start?hl=en"

    val STEPS = listOf(
        Step(
            "خوش آمدید",
            listOf(
                Item("فقط یک بار و حدود پنج دقیقه تنظیم لازم است"),
                Item("یک سرور واسط رایگان روی حساب گوگل خودتان می‌سازید تا سرعت و سهمیه‌ی شما به کسی وابسته نباشد"),
                Item("فقط یک حساب جیمیل لازم دارید و اگر ندارید همین حالا بسازید"),
            ),
        ),
        Step(
            "باز کردن صفحه‌ی گوگل",
            listOf(
                Item("دکمه‌ی زیر را بزنید تا صفحه در مرورگر باز شود"),
                Item("با حساب جیمیل خود وارد شوید"),
                Item("از منوی مرورگر نسخه‌ی دسکتاپ را روشن کنید چون ویرایشگر گوگل در حالت موبایل کار نمی‌کند", listOf("⋮", "Desktop site")),
                Item("روی دکمه‌ی پروژه‌ی جدید بزنید", listOf("New project")),
            ),
            action = "باز کردن صفحه‌ی گوگل" to Action.OPEN_SCRIPT,
            tip = "اگر صفحه‌ی گوگل فارسی بود همان دکمه‌ها را با ترجمه‌شان پیدا کنید",
        ),
        Step(
            "گذاشتن کد آماده",
            listOf(
                Item("دکمه‌ی کپی کد آماده را بزنید"),
                Item("داخل ویرایشگر گوگل بزنید و همه‌ی متن را انتخاب و پاک کنید", listOf("Select all", "Delete")),
                Item("کد را بچسبانید", listOf("Paste")),
                Item("ذخیره کنید", listOf("Save")),
            ),
            action = "کپی کد آماده" to Action.COPY_SCRIPT,
            tip = "رمز اختصاصی شما از قبل داخل کد است و چیزی را تغییر ندهید",
        ),
        Step(
            "انتشار کد",
            listOf(
                Item("بالا سمت راست این دکمه‌ها را به ترتیب بزنید", listOf("Deploy", "New deployment")),
                Item("کنار نوع انتشار روی چرخ‌دنده بزنید و این را انتخاب کنید", listOf("Web app")),
                Item("اجرا به نام را روی خودم بگذارید", listOf("Execute as", "Me")),
                Item("دسترسی را روی همه بگذارید و این مورد خیلی مهم است", listOf("Who has access", "Anyone")),
                Item("در پایان دکمه‌ی انتشار را بزنید", listOf("Deploy")),
            ),
        ),
        Step(
            "اجازه دادن به برنامه",
            listOf(
                Item("اگر گوگل درخواست اجازه کرد حساب خود را انتخاب کنید", listOf("Authorize access")),
                Item("بعد این دکمه‌ها را به ترتیب بزنید", listOf("Advanced", "Go to ... (unsafe)", "Allow")),
            ),
            tip = "این هشدار عادی است چون برنامه را خودتان ساخته‌اید و اگر نیامد از این مرحله رد شوید",
        ),
        Step(
            "وارد کردن شناسه",
            listOf(
                Item("در پنجره‌ی پایانی گوگل شناسه را کپی کنید", listOf("Deployment ID")),
                Item("به این برنامه برگردید تا خودکار وارد شود یا دکمه‌ی چسباندن را بزنید"),
            ),
            action = "چسباندن از کلیپ‌بورد" to Action.PASTE_ID,
            entry = true,
            tip = "لینک کامل برنامه‌ی وب هم قبول است",
        ),
    )

    const val PRIVACY_IMPORTED =
        "این کد اتصال روی سرور واسط فرد دیگری کار می‌کند و او از نظر فنی می‌تواند " +
            "محتوای ترافیک شما را ببیند پس برای بانکداری و حساب‌های حساس از آن استفاده نکنید"

    /** Shown when the connection test fails. */
    val TROUBLE = listOf(
        Item("دسترسی باید روی همه باشد", listOf("Who has access", "Anyone")),
        Item("بعد از هر تغییر در کد یک انتشار تازه بسازید و شناسه‌ی جدید را وارد کنید", listOf("New deployment")),
        Item("کد داخل ویرایشگر را دست نزده باشید و اگر زدید دوباره کپی کد آماده را انجام دهید"),
        Item("صفحه‌ی گوگل را در مرورگر باز کنید و اگر باز نشد مشکل از شبکه است"),
        Item("گواهی امنیتی دیبان باید نصب باشد و از منوی بیشتر می‌توانید آن را بررسی کنید"),
    )

    val TG_STEPS = listOf(
        Item("در تلگرام وارد تنظیمات شوید", listOf("Settings", "Data and Storage", "Proxy Settings")),
        Item("گزینه‌ی افزودن پروکسی را بزنید", listOf("Add Proxy")),
        Item("نوع را سوکس پنج بگذارید", listOf("SOCKS5")),
        Item("آدرس و پورت را اینطور وارد کنید", listOf("127.0.0.1 : 1080")),
        Item("نام کاربری و رمز را خالی بگذارید و ذخیره کنید", listOf("Save")),
    )
    const val TG_TIP = "وقتی حالت وی‌پی‌ان روشن است ترافیک تلگرام هم از همین مسیر می‌گذرد و افزودن پروکسی لازم نیست"

    val PROXY_STEPS = listOf(
        Item("وارد تنظیمات وای‌فای شوید و روی شبکه‌ی فعلی بزنید", listOf("Wi-Fi", "Modify network")),
        Item("بخش پراکسی را روی دستی بگذارید", listOf("Proxy", "Manual")),
        Item("آدرس و پورت را اینطور وارد کنید", listOf("127.0.0.1 : 8085")),
        Item("ذخیره کنید و مرورگر را دوباره باز کنید", listOf("Save")),
    )
    const val PROXY_TIP =
        "این روش فقط برای وای‌فای و بیشتر مرورگرها کار می‌کند و برای همه‌ی برنامه‌ها و داده‌ی همراه از حالت وی‌پی‌ان استفاده کنید"

    val CA_STEPS = listOf(
        Item("دکمه‌ی ذخیره‌ی گواهی را بزنید و آن را در پوشه‌ی دانلود ذخیره کنید"),
        Item("دکمه‌ی باز کردن تنظیمات امنیتی را بزنید"),
        Item(
            "این مسیر را دنبال کنید",
            listOf("Security", "Encryption & credentials", "Install a certificate", "CA certificate"),
        ),
        Item("اگر هشدار داد گزینه‌ی نصب در هر صورت را بزنید", listOf("Install anyway")),
        Item("فایل گواهی دیبان را از پوشه‌ی دانلود انتخاب کنید", listOf("Diban-CA.crt")),
        Item("به برنامه برگردید و دکمه‌ی بررسی را بزنید"),
    )
    const val CA_WHY =
        "برای باز کردن سایت‌های امن مرورگر باید به گواهی دیبان اعتماد کند و این گواهی فقط روی همین گوشی ساخته می‌شود و کلید آن از گوشی بیرون نمی‌رود"
    const val CA_TIP =
        "نام گزینه‌ها بسته به برند گوشی کمی فرق دارد و می‌توانید در جستجوی تنظیمات کلمه‌ی certificate را بزنید"
    const val CA_LIMITS =
        "اندروید به گواهی نصب‌شده توسط کاربر فقط در مرورگرها اعتماد می‌کند و بیشتر برنامه‌های دیگر آن را قبول نمی‌کنند. فایرفاکس هم باید در تنظیماتش اجازه‌ی گواهی‌های کاربر را بدهد"

    val CA_REMOVE = listOf(
        Item("برای حذف کامل گواهی امنیتی این مسیر را در تنظیمات دنبال کنید", listOf("Security", "Encryption & credentials", "User credentials")),
        Item("گواهی دیبان را انتخاب کنید و حذف کنید", listOf("MasterHttpRelayVPN", "Uninstall")),
    )
}

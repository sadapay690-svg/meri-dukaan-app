# Meri Dukaan — Android App

Ye folder aap ki HTML app ko **asli Android app (APK)** bana deta hai.
Aap ke computer par kuch install nahi karna. GitHub muft mein APK bana kar deta hai.

App ka poora code sirf aik file mein hai: **dukaan-app.html** (isi folder mein).
Build ke waqt wo khud app ke andar chali jati hai. Aap ko sirf yahi aik file badalni hoti hai.

---

## Step 1 — GitHub account

1. Phone ya computer par jayein: **github.com**
2. **Sign up** dabayein, email + password daalein, account bana lein (muft hai).

## Step 2 — Nayi repository banayein

1. Upar dayein taraf **+** dabayein → **New repository**
2. Repository name: `meri-dukaan-app`
3. **Private** chunein (koi aur aap ka code nahi dekh sakega)
4. **Create repository** dabayein

## Step 3 — Files upload karein

1. Nayi repository ke page par **uploading an existing file** link dabayein
   (ya: Add file → Upload files)
2. Computer par `D:\appp\meri-dukaan-app` folder kholein
3. Us folder ke **andar ki saari cheezein** select kar ke browser mein drag kar dein
   (folder khud nahi — us ke andar ka saara maal: `app`, `.github`, `dukaan-app.html`,
   `build.gradle`, `settings.gradle`, `gradle.properties`)
4. Neeche **Commit changes** dabayein

> Agar drag-drop se `.github` folder upload na ho (browser chupa deta hai), to
> Step 3B neeche dekhein.

## Step 4 — APK ka intezaar karein

1. Upar **Actions** tab dabayein
2. "APK banao" naam ka kaam chal raha hoga — peela dot 🟡
3. Taqreeban **5 se 10 minute** lagte hain. Green tick ✅ aa jaye to kaam ho gaya.
4. Ab **Code** tab par jayein → dayein taraf **Releases** → **Meri Dukaan — taaza APK**
5. Wahan `MeriDukaan-v1.apk` file hogi — usay download karein.

## Step 5 — Phone par install karein

1. APK phone par download karein (WhatsApp se bhej dein ya seedha phone se GitHub kholein)
2. File par tap karein → Android poochega "Unknown apps" ki ijazat → **Allow** dabayein
3. **Install** → ho gaya. Home screen par 🏪 **Meri Dukaan** icon aa jayega.

---

## Step 3B — Agar `.github` folder upload na ho

Ye folder sab se zaroori hai (isi mein APK banane ka nuskha hai). Haath se bana lein:

1. Repository mein **Add file → Create new file**
2. Naam ke khaane mein bilkul ye likhein (slash `/` bhi):
   `.github/workflows/build.yml`
3. `D:\appp\meri-dukaan-app\.github\workflows\build.yml` file Notepad se kholein,
   poora text copy kar ke GitHub ke bade khaane mein paste karein
4. **Commit changes**
5. Wahi kaam `.github/workflows/release.yml` ke liye dobara karein

## App update kaise karein (aage ke liye)

Jab bhi app mein nayi tabdeeli aaye:

1. Repository mein `dukaan-app.html` par click karein
2. Pencil ✏️ (Edit) dabayein → purana sab select kar ke nayi file ka text paste karein
3. **Commit changes** → GitHub khud naya APK bana dega (Actions → Releases)

Ya asaan tareeqa: **Add file → Upload files** se nayi `dukaan-app.html` upload kar dein,
purani khud replace ho jayegi.

> Naya APK install karne par **aap ka data mehfooz rehta hai** (products, sale, udhaar).
> Sirf shart: app ko uninstall na karein, upar se install karein.

---

## Step 6 — Phone par kya kya chalega

| Cheez | App mein |
|---|---|
| Poori app offline | ✅ chalti hai |
| Data phone mein mehfooz | ✅ (uninstall na karein) |
| Parchi/Invoice print + PDF | ✅ Android ka print dialog khulta hai |
| Bluetooth/USB thermal printer | ✅ printer ki app install ho to print list mein aata hai |
| Barcode camera se scan | ✅ pehli baar camera ki ijazat maangega → Allow |
| WhatsApp par bill | ✅ seedha WhatsApp khulta hai |
| CSV / Backup file save | ✅ phone ke **Downloads** folder mein |
| Backup file wapis restore | ✅ file chunne wali screen khulti hai |
| Cloud sync (email + password) | ✅ internet ho to |
| **Google se login** | ❌ nahi — Google apni policy ki wajah se app ke andar login nahi deta. Email + password wala login istemaal karein. |

---

## Step 7 — Play Store par daalna (baad mein, jab aap tayyar hon)

Play Store ke liye APK nahi, **AAB** file chahiye hoti hai, aur wo aap ki apni
signing key se sign honi chahiye. Wo key mein pehle hi bana di hai:

**`D:\appp\keystore`** folder — ise Google Drive par ya apne aap ko WhatsApp par
bhej dein. **Ye kho gayi to Play Store par app update karna namumkin ho jata hai.**

Us folder mein 3 cheezein hain:
- `meridukaan.jks` — asli key
- `keystore-base64.txt` — GitHub mein daalne wali shakal
- `PASSWORDS-YAHAN-HAIN.txt` — password aur alias

### 4 secrets GitHub mein daalein

Repository → **Settings** → bayein taraf **Secrets and variables** → **Actions**
→ **New repository secret**. Char baar ye kaam karein:

| Name | Value (kya paste karna hai) |
|---|---|
| `KEYSTORE_B64` | `keystore-base64.txt` ka poora text (aik hi lambi line hai) |
| `KS_PASS` | `PASSWORDS-YAHAN-HAIN.txt` mein diya Store password |
| `KEY_ALIAS` | `meridukaan` |
| `KEY_PASS` | wahi password dobara |

### AAB banayein

**Actions** → bayein taraf **"Play Store ke liye (signed)"** → dayein **Run workflow**
→ Version `1.0`, Version number `1` → **Run workflow** dabayein.
Kaam khatam hone par neeche **Artifacts** mein `MeriDukaan-Release` milega, us mein `.aab` file hai.

### Play Console

- Google Play Console ki **aik baar** ki fees: **$25** (taqreeban 7,000 rupay)
- `play-icon-512.png` (isi folder mein) app icon ke liye upload kar dein
- App ki Privacy Policy ka link bhi maangta hai — bata dein to bana dunga

> Har nayi update par **Version number** barhana zaroori hai: 1, phir 2, phir 3...
> warna Play Store reject kar deta hai.

---

## Masla aa jaye to

**Actions mein laal cross ❌ aa gaya:** us kaam par click karein, laal step kholein,
aakhri 20 lines copy kar ke mujhe bhej dein — theek kar dunga.

**App khulti hai magar safaid screen:** matlab `dukaan-app.html` repository ki
sab se bahar wali jagah (root) par nahi hai. Usay upload kar dein.

**"App not installed" phone par:** purani app pehle uninstall karein, phir install
karein (lekin pehle Settings → Data Export se backup file bana lein).


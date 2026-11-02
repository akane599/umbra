# Umbra

[English](README.md) · **Türkçe**

Ekran **panelini** gerçekten kapatır (güç modu OFF), sistem ise ekranı açık sanmaya devam eder:
ön plandaki ve arka plandaki uygulamalar tam hızda çalışır, ses/video/navigasyon/indirme sürer.
Parlaklık −1 ya da siyah katman değildir; scrcpy'nin `--turn-screen-off` yöntemidir.

## Nasıl çalışır

- Uygulama, kendi APK'sındaki `PanelServer` sınıfını **Shizuku** (shell ya da root) veya **su** ile
  `app_process` içinde başlatır ve stdin/stdout üzerinden konuşur.
- Sunucu `SurfaceControl.setDisplayPowerMode(token, OFF)` çağırır. Android 14+ için fiziksel ekran
  token'ları `services.jar` içindeki `DisplayControl` sınıfından alınır. Olmazsa Android 15+
  `DisplayManagerGlobal.requestDisplayPower` yedek yol olarak denenir (bazı cihazlarda ekran
  içeriğini dondurduğu için varsayılan değil).
- Sunucu aynı zamanda `/dev/input/event*` aygıtlarından ses ve güç tuşlarını okur (shell `input` grubunda).
- Uygulama ölürse panel kendiliğinden geri açılır. Sunucu, uygulamanın pid'ini `/proc` üzerinden saniyede bir
  izler (Shizuku, istemci ölünce stdin ucunu kapatmadığı için yalnızca EOF'a güvenilemez). Shizuku ayrıca
  sahibi ölen süreci SIGTERM ile öldürdüğünden sunucu `exec` ile değil bir ara kabuğun altında başlatılır;
  ölen kabuk olur, sunucu paneli açıp çıkar.

## Paneli kapatma

- **Hızlı ayarlar kutucuğu** (ayarlardan tek dokunuşla eklenebilir)
- Uygulamadaki büyük **güç düğmesi** (uygulama arka plana geçer, önceki uygulama ön planda kalır)
- Başlatıcı simgesine uzun bas → **“Paneli kapat”** kısayolu
- **Tasker / otomasyon** (aşağıda)

## Paneli geri açma

| Yöntem | Not |
|---|---|
| Ses+ ve Ses− birlikte | Varsayılan açık |
| Güç tuşu | Tek basışta geri gelir (sistem kilitlenip anında uyandırılır). Kapatılırsa normal kilitlenir. |
| Çift dokunma | Varsayılan açık. Şeffaf bir dokunma kalkanı dokunmaların alttaki uygulamaya gitmesini engeller (cep koruması). Kapatılırsa dokunmalar uygulamaya geçer. |
| Salla | 1,5 sn içinde 3 sert sallama |
| Zamanlayıcı | 5 dk – 2 saat |
| QS kutucuğu / bildirim / Tasker `ON` | |

**Kurtarma:** Ne olursa olsun güç tuşuna iki kez basmak (kilitle + aç) paneli her zaman normale döndürür;
uyanışta sistem paneli kendisi açar.

## Davranış ayarları

- **Cihazı uyanık tut** (varsayılan açık): Oturum boyunca ekran zaman aşımı uygulanmaz.
- **Paneli kapalı tut** (varsayılan açık): ROM paneli kendiliğinden açarsa 3 sn içinde yeniden kapatır;
  uykudayken ya da güç tuşuna basıldıktan sonraki 4 sn içinde devreye girmez.
- **Yetki kaynağı:** Otomatik (Shizuku → root), yalnız Shizuku, yalnız root.
- **Kapatma yöntemi:** Otomatik / SurfaceControl / DisplayManager.

İlk etkinleştirmede bildirim izni, çizim (overlay) izni ve pil optimizasyonu muafiyeti Shizuku/root ile
otomatik verilir (`pm grant`, `appops set`, `cmd deviceidle whitelist`). Muafiyet, Tasker yayınlarıyla
arka plandan ön plan servisi başlatabilmek için gerekir.

## Tasker

Eylemler: `io.github.akane599.paneloff.OFF`, `…ON`, `…TOGGLE`

- **Önerilen:** Eylem → *Send Intent* → Target: **Activity**, Package `io.github.akane599.paneloff`,
  Class `io.github.akane599.paneloff.ActionActivity`, Action yukarıdakilerden biri. Görünmez etkinlik, her
  koşulda çalışır.
- Alternatif: Target **Broadcast Receiver**, aynı paket ve eylem.

Bu giriş noktaları dışa açıktır; telefondaki herhangi bir uygulama da bu eylemleri gönderebilir.
Otomasyon kullanmıyorsan **Gelişmiş → Dış uygulamalardan komut** anahtarını kapat: o zaman yalnızca `ON`
(paneli geri açma) kabul edilir. Başlatıcı kısayolu ve QS kutucuğu dışa kapalı bir `ShortcutActivity`
alias'ı üzerinden çalıştığı için bu ayardan etkilenmez.

Not: Panel kapalıyken sistem ekranı *açık* sayar; Tasker'ın Display Off/On bağlamları tetiklenmez.

## Tanılama

**Kurulum ve tanılama → Tanılama** (ya da yetki kartına dokun): sunucuyu başlatır ve şunları gösterir: SDK, uid, `DisplayControl` yüklenebildi mi,
kaç fiziksel ekran token'ı bulundu, `requestDisplayPower` var mı, hangi giriş aygıtları okunabiliyor.
Bir şey çalışmazsa bu çıktıyı paylaş (“Kopyala” düğmesi var).

`adb logcat -s PanelService PanelBridge TouchShield` ile ayrıntılı günlük alınabilir.

## Derleme

```bash
# Android SDK yolu local.properties içinde (sdk.dir=...)
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Release imzası için kök dizine `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) koy;
yoksa release APK imzasız üretilir. Anahtar ve parolalar `.gitignore`'da.

Gereksinimler: Android 10+ (minSdk 29), Shizuku v11+ ya da root (KernelSU/Magisk). Hedef: Android 16 (API 36).

Arayüz İngilizce ve Türkçe; cihaz dili Türkçe ise ya da Android 13+ uygulama dili ayarından Türkçe seçilirse Türkçe açılır.

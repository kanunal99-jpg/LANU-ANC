# LANU-ANC — KANITLI TEKNİK DENETİM

**Tarih:** 15.10.2026  
**Kapsam:** GitHub dosya ağacı, seçili kaynak kodu, CI logları, Releases. Gerçek cihaz ve dış servislerin tam E2E testi yapılmadı.  
**Dosya sayısı (denetim anında):** 63  
**CI gözlemi:** 14.10.2026 Android APK başarılı; v1.1.0 LANU Tek Kulaklık APK, SHA-256 Release mevcut.  
**Anayasa:** [PROJE_ANAYASASI_VE_DUZELTILECEKLER.md](https://github.com/kanunal99-jpg/LANU-ANC/blob/main/PROJE_ANAYASASI_VE_DUZELTILECEKLER.md)

## Kanıtlarla Düzeltilmesi Gerekenler

### 1. Normal telefon görüşmesinin ses akışını bu uygulama değiştiremez — P1 (PLATFORM SINIRI / GERÇEK TEST)
- **Kanıt:** [README.md#L3-L18](https://github.com/kanunal99-jpg/LANU-ANC/blob/main/README.md#L3-L18); [CI koşusu](https://github.com/kanunal99-jpg/LANU-ANC/actions/runs/38624191397)
- **Gözlem:** README uygulamanın mikrofonunu yerel işleyip kulaklığa verdiğini söylüyor; bunun telefon operatörü görüşmesinin iki yönlü sesine müdahale ettiği sonucu çıkarılamaz.
- **Somut örnek / etkisi:** Normal SIM/GSM aramasında karşı taraftan gelen görüşme sesi işlenmiş gibi gösterilmemeli.
- **Düzeltme kabul testi:** Vaat/arayüz sınırı açık; kendi mikrofonu monitor özelliği ile telefon görüşmesi ayrı. Android normal çağrı ses yakalama izni üçüncü taraf uygulamalara açık değil.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 2. Tek kulak monitor sadece arama yokken açılıyor — P1 (DOĞRULANMIŞ İYİ SINIRLAMA)
- **Kanıt:** [app/src/main/java/com/lanu/anc/SingleEarPolicy.kt#L4-L11](https://github.com/kanunal99-jpg/LANU-ANC/blob/main/app/src/main/java/com/lanu/anc/SingleEarPolicy.kt#L4-L11); [CI koşusu](https://github.com/kanunal99-jpg/LANU-ANC/actions/runs/38624191397)
- **Gözlem:** canMonitor koşulu: kulaklık bağlı + mikrofon izni + callActive=false; giriş/çıkış mono.
- **Somut örnek / etkisi:** Bir adet sağ veya sol kulaklık takılınca ses yolu çalışabilmeli; aktif telefon görüşmesinde uygulama monitor başlatmamalı.
- **Düzeltme kabul testi:** Bluetooth A2DP/HFP, kablolu USB, yalnız sağ/sol ve görüşme geçişi gerçek telefon smoke.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 3. Donanımda gerçek ANC topolojisi garanti değil — P2 (DÜŞÜK GECİKME SINIRI)
- **Kanıt:** [app/src/main/java/com/lanu/anc/AncCapabilities.kt#L9-L34](https://github.com/kanunal99-jpg/LANU-ANC/blob/main/app/src/main/java/com/lanu/anc/AncCapabilities.kt#L9-L34); [CI koşusu](https://github.com/kanunal99-jpg/LANU-ANC/actions/runs/38624191397)
- **Gözlem:** low latency API desteği, fiziksel ANC referans/hata mikrofon çifti anlamına gelmez; kod da bunu ifade ediyor.
- **Somut örnek / etkisi:** Kanal sayısı uygun görünse bile gerçek faz karşılaştırması kullanılamayabilir.
- **Düzeltme kabul testi:** Örnekleme frekansı, gecikme, hoparlör feedback ve gain güvenliği ölçümü; unsupported cihazda güvenli uyarı.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 4. v1.1.0 tek kulaklık APK ve manifest var — P2 (RELEASE KANITI)
- **Kanıt:** [releases/tag/v1.1.0](https://github.com/kanunal99-jpg/LANU-ANC/releases/tag/v1.1.0); [CI koşusu](https://github.com/kanunal99-jpg/LANU-ANC/actions/runs/38624191397)
- **Gözlem:** Derleme başarılı, APK sürüm dosyası var; ses kalitesi/telefon cihaz matrisi ayrıca gerekir.
- **Somut örnek / etkisi:** Samsung ve farklı kulaklıklarda yalnız sağ/sol mikrofon sesinin duyulabilirliği test edilmeli.
- **Düzeltme kabul testi:** Cihaz model, Android sürümü, route, izin, ölçüm ve başarı bağlantıları.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

## Zorunlu 4 Faz
1. Araştır/Envanter: kapsam, kanıt ve etki; bu rapor başlangıç seviyesinde seçili dosya/CI incelemesidir.
2. Kök neden ve düzeltme: P0/P1 önce; en küçük geri alınabilir kod değişimi; gizli veriyi koru; ücret için onay al.
3. Build + test: lint, unit/integrasyon/UI/E2E, offline/kesinti/fallback, gerekirse gerçek cihaz.
4. Doğrula ve yayınla: tüm testler yeşil; Android APK Releases altında SHA-256 ile doğrudan indirilebilir; smoke ve bağlantı doğrulaması.

**Bulgu kapatma:** Kod satırı + kök neden + düzeltme commit'i + test + CI + sürüm/ürün smoke kanıtı olmadan hiçbir madde bitmiş sayılmaz.

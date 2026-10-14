# LANU Tek Kulaklık — 1.1.0

Sağ **veya** sol tek kulaklıkla çalışan yerel mikrofon dinleme uygulaması. Tek mikrofon girişi ve mono çıkış kullanır; iki kulaklık, iki bağımsız mikrofon veya ANC kalibrasyonu gerekmez.

## Ne yapar?

Uygulamanın kendi mikrofon sesini işleyip bağlı kulaklığa verir. Android `NoiseSuppressor` gerçekten etkinleştirilebiliyorsa bunu kullanır. Desteklenmezse yerel temel filtre konuşma aralarındaki düşük seviyeli sesi azaltır. Bu temel filtre kalabalıktan konuşma ayırma iddiası taşımaz. Filtre açılıp kapatılabilir; dinleme kazancı düşük başlar ve sınırlıdır.

Bluetooth A2DP çıkışında telefon mikrofonu kullanılır. Bluetooth gecikmesi duyulabilir. Kablolu/USB kulaklık mikrofonu varsa tercih edilir. Sağ/sol seçimi gerekmez: mono ses iki kanala da aynı içeriği verir ve tek takılan kulaklıkta duyulur.

## Kullanım

1. Telefonla eşleşmiş kulaklığın sağ veya sol tek parçasını bağlayın.
2. LANU'yu açın; mikrofon ve gerektiğinde yakındaki cihaz iznini verin.
3. **Dinlemeyi başlat** düğmesine basın. Telefona veya seçilen kulaklık mikrofonuna konuşun.
4. Filtreyi açıp kapatın; seviyeyi düşükten ayarlayın. Bildirimden veya uygulamadan durdurun.

Kulaklık yoksa dinleme başlamaz. Bağlantı veya ses yolu değişirse akış durur. Desteklenen örnekleme hızı bulunamazsa başka hızlar denenir. Mikrofon başka uygulama tarafından susturulursa kullanıcıya hata gösterilir. Kayıt, hesap, internet veya ücretli API kullanılmaz.

## Sınır

Bu uygulama normal telefon/WhatsApp aramasına filtre eklemez. Donanımsal ANC üretmez; açık kalan kulağın duyduğu ortam sesini engellemez. Önceki deneysel iki kanallı native ANC bileşenleri kaynakta korunmuştur, tüketici ekranından çalıştırılmaz.

## Doğrulama

CI: Kotlin/C++ APK derlemesi, birim testleri, Android emülatöründe açılış/yeniden oluşturma, kulaklıksız güvenli hata, durdurma ve gerçek Android mono kayıt/oynatma nesneleri. Temel filtrenin sessiz aralıkları azaltması ve konuşma seviyesinde açılması sentetik sinyallerle sınanır. Bunlar fiziksel telefon/kulaklık ses kalitesi testi değildir.

İndirilebilir APK ve SHA-256 manifesti: [Releases](https://github.com/kanunal99-jpg/LANU-ANC/releases).

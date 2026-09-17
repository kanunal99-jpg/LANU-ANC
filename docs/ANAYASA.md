# LANU Audio Engine — ANAYASA

## 1. Gerçeklik ilkesi
LANU yalnızca doğrulanabilir ve test edilebilir özellikleri sunar. Ölçülmemiş dB, yüzde, latency, battery veya kalite sonucu gerçek sonuç gibi gösterilemez.

## 2. ANC tanımı
Hardware ANC ile software noise suppression birbirine eşit kabul edilmez.
- Hardware ANC: kulaklık içindeki uygun mikrofon/DSP/hoparlör donanımıyla aktif anti-noise kontrolü.
- Software NS: yakalanan ses sinyalinin gerçek zamanlı işlenmesi ve gürültü bileşenlerinin azaltılması.

LANU, ANC donanımı olmayan bir kulaklığa donanımsal ANC kazandırdığını iddia etmeyecektir.

## 3. Audio pipeline
Tercih edilen yapı:
Capture → pre-processing → analysis → AEC/NS/AGC → optional local AI NS → post-processing → output.

Her aşama açılıp kapanabilir ve hata verdiğinde güvenli fallback'e geçebilir.

## 4. Low latency
Gerçek zamanlı audio callback içinde bloklayan I/O, ağ çağrısı, disk yazımı veya gereksiz allocation yapılmaz. Düşük gecikme için Oboe/AAudio ve native C++ önceliklidir.

## 5. Adaptive processing
En agresif gürültü bastırma varsayılan değildir. Konuşma bozulması, pumping/artifact ve müzik bozulması ölçülerek uygun işlem seviyesi seçilir.

## 6. Local-first
Temel gürültü azaltma internet bağlantısına bağlı olmayacaktır. AI modeli kullanılacaksa ilk tercih cihaz üzerinde çalışan modeldir.

## 7. Device capability
Android cihazların ses yetenekleri farklıdır. NoiseSuppressor/AEC/AGC, sample rate, channel count, input/output route, Bluetooth/BLE/USB desteği runtime'da tespit edilir.

## 8. Routing
Uygulama, kullanıcının seçtiği ve Android'in izin verdiği audio route'u kullanır. Sistem tarafından desteklenmeyen global audio interception varsayımı yapılmaz.

## 9. GSM çağrı sınırı
Üçüncü taraf uygulamanın bütün Android cihazlarda normal GSM çağrısının uplink/downlink ses yoluna girip karşı tarafa işlenmiş mikrofon sesi gönderebildiği varsayılmayacaktır. LANU'nun tam kontrol ettiği iletişim senaryosu kendi VoIP pipeline'ıdır.

## 10. Privacy
Ham mikrofon verisi varsayılan olarak buluta gönderilmez. Kayıt/ölçüm yapılacaksa kullanıcıya açıkça bildirilir ve mümkün olduğunca cihaz üzerinde tutulur.

## 11. Foreground service
Arka planda mikrofon işleme gerektiğinde Android'in güncel foreground-service ve microphone permission kuralları uygulanır. Gizli/sessiz mikrofon çalışması yapılmaz.

## 12. Fallback zinciri
Primary native/WebRTC processing → supported Android AudioEffect → local AI alternative → basic DSP safe mode → passthrough/safe error.

Fallback'ın gerçekten çalıştığı test edilmeden dokümante edilmiş kabul edilmez.

## 13. Test-first
Her önemli ses özelliği gerçek cihaz testine bağlanır. En azından sessiz ortam, fan/klima, motor, trafik, kalabalık, konuşma, klavye ve rüzgar senaryoları değerlendirilir.

## 14. No fake metrics
UI örnek değerleri üretim ölçümü olarak göstermeyecek. dB ve SNR ölçümleri için kalibre edilmiş test metodolojisi veya açıkça 'relative/estimated' etiketi gerekir.

## 15. Safe failure
Audio stream başlatılamazsa uygulama kilitlenmez. Kullanıcıya sebep ve uygulanabilir çözüm gösterilir; mümkünse güvenli passthrough kullanılır.

## 16. Release gate
APK release adayı sayılmadan önce build, install, microphone permission, route detection, start/stop, background lifecycle, Bluetooth route, audio failure recovery, rotation/process death ve uzun süreli stress testleri tamamlanır.
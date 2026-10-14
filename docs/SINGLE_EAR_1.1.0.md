# 1.1.0 — Tek kulaklık: dört faz

1. Araştır: Önceki iki kanallı ANC şartı tek kulaklık kullanımıyla uyuşmuyordu. Android 12+ iletişim çıkışlarından mikrofon aranıyordu; kalibrasyon başlamadan `routedDevice` sorgulanıyordu. GSM/WhatsApp ses yoluna müdahale yapılamaz.
2. Düzelt/Uygula: Varsayılan işlev bir mikrofon/mono kulaklık dinlemesidir. Fiziksel ANC ekranı kaldırıldı; deneysel kaynaklar korundu. Ses giriş/çıkış cihazları ayrı keşfedilir. Android NS → yerel temel gate → güvenli duruş zinciri; hız/kaynak alternatifleri; sınırlı kazanç; izin/hata yönetimi; açık bildirim; kulaklık kopunca duruş.
3. Test/Doğrula: Mevcut DSP/ANC testlerine tek kulaklık politikası, kazanç doğrulaması ve temel filtrenin gerçek sinyal testleri eklendi. Emülatör testleri cihaz üstündeki API açılışı ve mono stream nesnelerini sınar. Fiziksel kulaklık ve A55 testi bu ortamda yoktur; gerçek performans kanıtı gibi gösterilemez.
4. Yayınla: PR testleri geçtikten sonra main'e alınır. Main CI tekrar build/test/emülatör kapısını geçip sürüme özel APK, SHA-256 ve build manifestini Releases'e ekler. Yeni sürüm önceki release'in dosyalarını değiştirmez.

Kontrol: Mikrofon izin reddi; tek sağ/tek sol; kablolu/USB/Bluetooth; kulaklık kopması; filtre aç/kapat; durdur/başlat; arka plan bildirimi; normal arama ile çakışma. Son fiziksel test kullanıcı cihazında yapılmalıdır.

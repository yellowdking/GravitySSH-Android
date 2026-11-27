-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# App @Serializable types across model/sync/terminal/vault
-keep,includedescriptorclasses class com.gravityssh.app.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.gravityssh.app.sync.**$$serializer { *; }
-keep,includedescriptorclasses class com.gravityssh.app.terminal.**$$serializer { *; }
-keep,includedescriptorclasses class com.gravityssh.app.vault.**$$serializer { *; }
-keepclassmembers class com.gravityssh.app.model.**,com.gravityssh.app.sync.**,com.gravityssh.app.terminal.**,com.gravityssh.app.vault.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# SSHJ & Ed25519 (dynamic factories, ciphers, KEX, key format providers)
-keep class com.hierynomus.sshj.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }

# SLF4J 2.x ServiceLoader NOP binding
-keep class org.slf4j.nop.** { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }

# ZXing QR Code reader/writer (lets R8 strip unused 1D/Aztec/PDF417 codecs)
-keep class com.google.zxing.qrcode.** { *; }

# AndroidX Biometric (support fragment reflection under R8)
-keep class androidx.biometric.** { *; }

# BouncyCastle: targeted JCA/PEM/OpenSSH keep rules (avoids keeping ~4,000 unused BC classes)
# Provider core, config & SPI base utilities
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider$* { *; }
-keep class org.bouncycastle.jce.ECNamedCurveTable { *; }
-keep class org.bouncycastle.jce.spec.** { *; }
-keep class org.bouncycastle.jce.interfaces.** { *; }
-keep class org.bouncycastle.jcajce.provider.config.** { *; }
-keep class org.bouncycastle.jcajce.provider.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }

# Asymmetric algorithms & $Mappings registrations (RSA, EC/ECDSA/ECDH, DSA, DH, Ed25519/X25519, X.509)
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DSA { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DSA$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DH { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DH$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dh.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509 { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.** { *; }

# Symmetric ciphers, Poly1305 & KDFs (AES/GCM/CTR/CBC, ChaCha20-Poly1305, 3DES, Blowfish, DES, SCRYPT, OpenSSLPBKDF, PBKDF1/2, PKCS12)
-keep class org.bouncycastle.jcajce.provider.symmetric.AES { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DESede { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DESede$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DES { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DES$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Blowfish { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Blowfish$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Poly1305 { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Poly1305$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.SCRYPT { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.SCRYPT$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.OpenSSLPBKDF { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.OpenSSLPBKDF$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF1 { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF1$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF2 { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF2$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPKCS12 { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPKCS12$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.util.** { *; }

# Digests & HMACs (SHA-1, SHA-256, SHA-384, SHA-512, MD5)
-keep class org.bouncycastle.jcajce.provider.digest.SHA1 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA1$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA384 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA384$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.BCMessageDigest { *; }
-keep class org.bouncycastle.jcajce.provider.digest.DigestAlgorithmProvider { *; }

# PEM, PKCS#8, targeted ASN.1 (avoid keeping ~1,500+ unused CMS/OCSP/GOST/PQC ASN.1 classes), OpenSSH key params & utilities
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.pkcs.** { *; }
-keep class org.bouncycastle.asn1.* { *; }
-keep class org.bouncycastle.asn1.pkcs.** { *; }
-keep class org.bouncycastle.asn1.x509.** { *; }
-keep class org.bouncycastle.asn1.x9.** { *; }
-keep class org.bouncycastle.asn1.sec.** { *; }
-keep class org.bouncycastle.asn1.nist.** { *; }
-keep class org.bouncycastle.asn1.edec.** { *; }
-keep class org.bouncycastle.asn1.oiw.** { *; }
-keep class org.bouncycastle.asn1.misc.** { *; }
-keep class org.bouncycastle.asn1.cryptlib.** { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-keep class org.bouncycastle.crypto.util.OpenSSH* { *; }
-keep class org.bouncycastle.util.** { *; }

# Suppress R8 warnings for optional desktop-JDK / reflection-only references
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**

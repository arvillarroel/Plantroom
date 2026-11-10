# Plantroom: offline Android maintenance search

Members starter app from The AI Automators. A native Android app that searches maintenance photos, text-based PDF reports and voice notes with EmbeddingGemma 2 on the device. Includes Kotlin/Compose source, a debug APK and a fictional sample collection. There is no generative chat model in this Android app.

## Install the included app

1. Use an Android 12 or newer device with around 1.5 GB free. Extract the ZIP on your computer.
2. Download `embeddinggemma-2-740m.litertlm` from the official [LiteRT community model repository](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main). Use the full 740M model, not a text-only variant.
3. Transfer `plantroom.apk` and the model file to your phone's Downloads folder by USB. Open the APK in your file manager. Android may ask you to permit this installation. This is a locally signed prototype, not a Play Store app.
4. Open Plantroom, choose Set up local search, then Import model file. Select the model from Downloads. The expected SHA256 is `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c`.
5. Select Mercer Court and Index collection. Repeat for Calder House. Keep the app in the foreground during indexing. Once ready, disconnect and search offline.

Try `blocked ventilation` in Mercer Court, or `cracked rubber foot under a water pump` in Calder House. Use a photo query to find visually related evidence. Tap a result to open its original. Add your own evidence and index it before searching.

The additional `tap-hose-photos.zip` contains the four synthetic hose photographs used in the filmed photo-search example. Copy it to Downloads, then use Add evidence, Import a building pack, and index the new records in Library. These are fictional examples, not plumbing guidance.

## Build and customise

Install JDK 17, Android SDK platform 35 and Gradle 8.11.1. Set ANDROID_HOME to your SDK folder, or create your own `local.properties` with `sdk.dir`. No machine-specific SDK location is included.

From this directory:

```sh
gradle assembleDebug
```

The build downloads its Android dependencies, bundles the unpacked `sample-data` into the app and writes `app/build/outputs/apk/debug/app-debug.apk`. You can also import the project into Android Studio using a local Gradle 8.11.1 installation. A Gradle wrapper is not bundled. Review SDK licences yourself during installation.

The sample source files are in `sample-data`. App behaviour and screens are in `app/src/main/java/com/plantroom/pocket`. Start by replacing the fictional buildings and sample collection, then adapt the workflow for your use case.

## Costs, privacy and limits

There are no inference API fees and no account is needed. Internet is used on your computer for the model and build downloads. The Android app has no INTERNET permission and no cloud inference fallback. The model, source files and index stay in app-private storage; uninstalling deletes them. The keyboard's speech-to-text feature is separate from Plantroom and its offline availability depends on your keyboard and downloaded language packs.

Search quality and speed vary with the device and files. This is a starter project, not a production maintenance or diagnostic system. Similarity is not a diagnosis or an asset identity. PDFs must contain selectable text, with a 100-page/50 MB limit; scanned PDFs need OCR elsewhere. Audio imports are limited to 60 seconds/50 MB; in-app recordings stop at 55 seconds. External building packs allow up to 500 records/600 MB uncompressed. Leave the app open while indexing.

The video demonstrates a Samsung S21 Ultra. The package does not promise compatibility with every Android 12 device. CPU is the default backend.

## Licence

Code is under the community Members License in `LICENSE.md`. The model and third-party libraries retain their own terms, listed in `THIRD_PARTY.md`. The bundled collection is synthetic and fictional. Model weights, signing keys, SDKs and personal phone data are not included.

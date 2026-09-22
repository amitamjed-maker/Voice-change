# Voice Changer (Male → Female, WhatsApp Share)

একটা Android অ্যাপ — আপনার নিজের voice record করে সেটা মেয়ের কণ্ঠে convert করে, তারপর WhatsApp/Messenger এ voice message হিসেবে পাঠানোর জন্য Share sheet খুলে দেয়।

## কেন এটা "call এর সময় on করলেই" কাজ করে না
WhatsApp/Messenger/normal call এর audio stream end-to-end encrypted এবং Android নিজে সেই pipeline-এ third-party app ঢুকতে দেয় না (root ছাড়া)। তাই এই অ্যাপের workflow হলো: **record → convert → voice message হিসেবে share**, লাইভ কলের ভিতরে না।

## কীভাবে কাজ করে
1. **Record** বাটনে চাপুন, কথা বলুন, আবার চাপুন থামাতে।
2. **Convert** বাটনে চাপুন — অ্যাপ নিজের ভিতরেই pitch shift করে (resample + WSOLA time-stretch অ্যালগরিদম, কোনো internet/cloud লাগে না, তাই fast এবং privacy-safe)।
3. **Play** দিয়ে শুনে নিশ্চিত হোন।
4. **Share** চাপলে সরাসরি WhatsApp খুলবে, contact বেছে পাঠিয়ে দিন। WhatsApp না থাকলে সাধারণ Share menu খুলবে।

## Pitch shift অ্যালগরিদম (কেন এটা পরিষ্কার শোনাবে)
সাধারণ "speed up" পদ্ধতিতে voice চিপমাঙ্ক-এর মতো শোনায় কারণ duration ছোট হয়ে যায়। এখানে দুই ধাপ ব্যবহার হয়েছে:
- **Resample** দিয়ে pitch বাড়ানো হয় (সাথে duration কমে যায়)
- **WSOLA (Waveform-Similarity Overlap-Add)** দিয়ে duration আবার আগের মতো করে দেওয়া হয়, pitch অপরিবর্তিত রেখে

এটাই মূলত SoundTouch/বেশিরভাগ ভালো voice-changer অ্যাপ যে কৌশল ব্যবহার করে। ফলাফল যথেষ্ট পরিষ্কার এবং প্রাকৃতিক শোনায়, নিখুঁত studio-quality না হলেও।

`PitchShifter.MALE_TO_FEMALE_PITCH` (`1.45`) মান পরিবর্তন করে ভয়েসের pitch আরও বেশি/কম করা যায় — `MainActivity.kt` বা `PitchShifter.kt` থেকে।

## GitHub Actions দিয়ে build (phone থেকেই)
এই repo push করার সাথে সাথে `.github/workflows/build.yml` অটোমেটিক চলবে এবং debug APK বানাবে।
1. এই পুরো ফোল্ডার একটা নতুন GitHub repo-তে push করুন।
2. GitHub-এর **Actions** ট্যাবে যান, workflow শেষ হওয়া পর্যন্ত অপেক্ষা করুন (কয়েক মিনিট)।
3. Run সম্পন্ন হলে নিচে **Artifacts** সেকশনে `voice-changer-debug-apk` পাবেন — download করে ফোনে install করুন ("Install from unknown sources" enable করতে হতে পারে)।

## প্রজেক্ট গঠন
```
VoiceChanger/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/belawal/voicechanger/
│       │   ├── MainActivity.kt      # UI, record, play, share
│       │   ├── PitchShifter.kt      # DSP: pitch shift অ্যালগরিদম
│       │   └── WavUtils.kt          # WAV read/write
│       └── res/                     # layout, strings, icon
├── build.gradle.kts
├── settings.gradle.kts
└── .github/workflows/build.yml      # auto-build workflow
```

## Permission
শুধু `RECORD_AUDIO` লাগে। Share করার সময় Android নিজেই FileProvider দিয়ে নিরাপদে WhatsApp-কে file access দেয়, আলাদা storage permission লাগে না।

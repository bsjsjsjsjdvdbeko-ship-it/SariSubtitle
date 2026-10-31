import re

file_path = "app/build.gradle.kts"
with open(file_path, "r") as f:
    content = f.read()

# التحقق إذا كان التوقيع موجوداً مسبقاً
if "signingConfigs" in content:
    print("⚠️ التوقيع موجود مسبقاً. لن يتم التعديل.")
else:
    # إضافة كتلة التوقيع قبل buildTypes
    signing_block = """
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: ""
        }
    }
"""
    # البحث عن android {
    content = content.replace("android {", "android {\n" + signing_block, 1)
    
    # ربط التوقيع بـ buildTypes (إذا كان debug، نستخدم التوقيع)
    if "buildTypes" in content:
        content = content.replace("buildTypes {", "buildTypes {\n        getByName(\"debug\") {\n            signingConfig = signingConfigs.getByName(\"release\")\n        }", 1)
    
    with open(file_path, "w") as f:
        f.write(content)
    print("✅ تمت إضافة إعدادات التوقيع.")

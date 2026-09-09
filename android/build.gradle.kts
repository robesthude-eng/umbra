// Корневой build-файл: версии плагинов декларируются здесь, применяются в модулях.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Push-уведомления: в модуле app плагин применяется только при наличии
    // google-services.json.
    alias(libs.plugins.google.services) apply false
}

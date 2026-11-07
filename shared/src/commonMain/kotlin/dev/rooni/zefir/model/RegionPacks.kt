package dev.rooni.zefir.model

import dev.rooni.zefir.res.Res
import dev.rooni.zefir.res.*
import org.jetbrains.compose.resources.StringResource

data class AppPack(
    val id: String,
    val title: StringResource,
    val patterns: List<String>,
) {
    fun matches(pkg: String) = patterns.any { if (it.endsWith(".")) pkg.startsWith(it) else pkg == it }
}

data class RegionPack(
    val country: String,
    val title: StringResource,
    val packs: List<AppPack>,
    val directRules: List<String>,
)

object Regions {
    val all = listOf(
        RegionPack(
            country = "RU",
            title = Res.string.region_ru,
            packs = listOf(
                AppPack(
                    "ru_social", Res.string.pack_social,
                    listOf("ru.oneme.app", "com.vkontakte.android", "com.vk.", "com.uma.musicvk", "ru.vk.", "ru.ok.android", "ru.mail.", "ru.zen.android", "ru.rutube.app", "ru.wb.workspace"),
                ),
                AppPack(
                    "ru_yandex", Res.string.pack_yandex,
                    listOf("ru.yandex.", "com.yandex.", "ru.kinopoisk", "ru.beru.android", "ru.foodfox.client"),
                ),
                AppPack(
                    "ru_shops", Res.string.pack_shops,
                    listOf(
                        "ru.ozon.app.android", "com.wildberries.", "com.avito.android", "ru.megamarket.marketplace", "com.lamoda.lite",
                        "ru.dns.shop.android", "ru.filit.mvideo.b2c", "ru.mvm.eldo", "ru.sportmaster.app", "goldapple.ru.goldapple.customers",
                        "ru.pyaterochka.app.browser", "ru.tander.magnit", "ru.perekrestok.app", "ru.sbcs.store", "ru.smmd.superdelivery",
                        "ru.cian.main", "ru.hh.android", "ru.auto.ara", "ru.dublgis.dgismobile", "com.logistic.sdek", "com.octopod.russianpost.client.android",
                    ),
                ),
                AppPack(
                    "ru_food", Res.string.pack_food,
                    listOf("ru.dodopizza.app", "ru.kfc.kfc_delivery", "ru.burgerking", "com.apegroup.mcdonaldsrussia"),
                ),
                AppPack(
                    "ru_transport", Res.string.pack_transport,
                    listOf(
                        "com.taxsee.taxsee", "com.citymobil", "com.drivee.taxi.rides", "soft.gelios.com.scooter", "com.punicapp.whoosh", "ru.urentbike.app",
                        "ru.aviasales", "ru.rzd.pass", "ru.tutu.",
                    ),
                ),
                AppPack(
                    "ru_banks", Res.string.pack_banks,
                    listOf(
                        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.tinkoff.", "ru.alfabank.mobile.android", "ru.vtb24.mobilebanking.android",
                        "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews", "ru.ozon.fintech.finance", "ru.rshb.dbo", "ru.lewis.dbo", "ru.otpbank.mobile",
                        "ru.akbars.citizencard", "ru.nspk.mirpay", "ru.cardsmobile.mw3", "com.umoney.enduser",
                    ),
                ),
                AppPack(
                    "ru_gov", Res.string.pack_gov,
                    listOf("ru.rostel", "ru.gosuslugi.", "ru.sigma.gisgkh", "ru.fns.lkfl", "com.gnivts.selfemployed", "com.programmisty.emiasapp", "ru.mosreg.moe"),
                ),
                AppPack(
                    "ru_school", Res.string.pack_school,
                    listOf(
                        "com.mirit.uchusvkuzbasse_new", "com.mirit42.mjk", "ru.dnevnik.app", "ru.mes.dnevnik.fgis", "ru.mes.diary.po", "mosreg.dnevnik.app", "mo.dnevnik.app",
                        "com.edumobile3", "com.eljur.client", "ru.eljur.", "ru.barsopen.mydiary", "com.sferoom.sferoom_mobile",
                    ),
                ),
                AppPack(
                    "ru_operators", Res.string.pack_operators,
                    listOf("ru.mts.mymts", "ru.mts.mtstv", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2", "com.dartit.RTcabinet", "ru.ivi.client", "ru.more.play"),
                ),
            ),
            directRules = listOf(
                "DOMAIN-SUFFIX,ru,DIRECT",
                "DOMAIN-SUFFIX,xn--p1ai,DIRECT",
                "DOMAIN-SUFFIX,su,DIRECT",
                "GEOIP,RU,DIRECT",
            ),
        ),
    )

    fun of(country: String?): RegionPack? = all.firstOrNull { it.country.equals(country, ignoreCase = true) }
}

fun AppSettings.effectiveRegion(detected: String): RegionPack? = when (region) {
    null -> Regions.of(detected)
    "" -> null
    else -> Regions.of(region)
}

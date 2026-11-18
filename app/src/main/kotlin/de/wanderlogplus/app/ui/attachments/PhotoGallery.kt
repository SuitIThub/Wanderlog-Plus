package de.wanderlogplus.app.ui.attachments

import de.wanderlogplus.shared.sync.EntityType

const val PHOTOS_ROUTE = "photos/{type}/{targetId}/{index}"

fun photosRoute(type: EntityType, targetId: String, index: Int) = "photos/${type.name}/${android.net.Uri.encode(targetId)}/$index"

/** "1 Foto", "12 Fotos". */
fun photoCountLabel(n: Int) = if (n == 1) "1 Foto" else "$n Fotos"

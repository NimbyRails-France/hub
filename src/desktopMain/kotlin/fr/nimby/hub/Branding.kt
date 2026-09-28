package fr.nimby.hub

import fr.nimby.hub.i18n.tr

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** One bundled source image for the window, notification area and sidebar. */
internal val hubLogo: BufferedImage by lazy {
    checkNotNull(object {}.javaClass.getResourceAsStream("/branding/hub.png")) {
        tr("Logo du Hub absent du paquet")
    }.use { checkNotNull(ImageIO.read(it)) { tr("Logo du Hub illisible") } }
}

package fr.nimby.hub

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** One bundled source image for the window, notification area and sidebar. */
internal val hubLogo: BufferedImage by lazy {
    checkNotNull(object {}.javaClass.getResourceAsStream("/branding/hub.png")) {
        "Logo du Hub absent du paquet"
    }.use { checkNotNull(ImageIO.read(it)) { "Logo du Hub illisible" } }
}

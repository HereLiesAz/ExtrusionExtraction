package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.IMAGE_SIZE
import com.hereliesaz.extrusionextraction.shared.ViewImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Decode ordered references identically in the desktop and headless notebook adapters. */
internal fun decodeReference(file: File): ViewImage {
    val image=ImageIO.createImageInputStream(file).use { input->
        val readers=ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Unsupported image: ${file.name}" }
        val reader=readers.next()
        try {
            reader.input=input
            val width=reader.getWidth(0);val height=reader.getHeight(0)
            require(width.toLong()*height<=40_000_000) { "Image exceeds pixel limit." }
            val step=maxOf(1,maxOf(width,height)/(IMAGE_SIZE*2))
            val params=reader.defaultReadParam
            params.setSourceSubsampling(step,step,0,0)
            reader.read(0,params)
        } finally { reader.dispose() }
    }
    val resized=BufferedImage(IMAGE_SIZE,IMAGE_SIZE,BufferedImage.TYPE_INT_ARGB)
    val graphics=resized.createGraphics()
    try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image,0,0,IMAGE_SIZE,IMAGE_SIZE,null)
    } finally { graphics.dispose() }
    return ViewImage(file.name,resized.getRGB(0,0,IMAGE_SIZE,IMAGE_SIZE,null,0,IMAGE_SIZE))
}

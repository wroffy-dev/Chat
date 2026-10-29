package app.vaultchat.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons the core Material set lacks (paths from Material Icons, Apache 2.0). */
object AppIcons {
    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        )
    }

    val MicOff: ImageVector by lazy {
        icon(
            "MicOff",
            "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01L9.01,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z",
        )
    }

    val VideocamOff: ImageVector by lazy {
        icon(
            "VideocamOff",
            "M21,6.5l-4,4V7c0,-0.55 -0.45,-1 -1,-1H9.82L21,17.18V6.5zM3.27,2L2,3.27 4.73,6H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.21,0 0.39,-0.08 0.54,-0.18L19.73,21 21,19.73 3.27,2z",
        )
    }

    val FlipCamera: ImageVector by lazy {
        icon(
            "FlipCamera",
            "M16,7h-1l-1,-1h-4L9,7H8c-1.1,0 -2,0.9 -2,2v6c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V9c0,-1.1 -0.9,-2 -2,-2zM12,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM8.57,0.51l4.48,4.48V2.04c4.72,0.47 8.48,4.23 8.95,8.95h2C23.34,3.02 15.49,-1.59 8.57,0.51zM10.95,21.96c-4.72,-0.47 -8.48,-4.23 -8.95,-8.95H0c0.66,7.97 8.51,12.58 15.43,10.48l-4.48,-4.48v2.95z",
        )
    }

    val VolumeUp: ImageVector by lazy {
        icon(
            "VolumeUp",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val CallEnd: ImageVector by lazy {
        icon(
            "CallEnd",
            "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 -0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z",
        )
    }

    val QrCode: ImageVector by lazy {
        icon(
            "QrCode",
            "M3,11h8V3H3v8zM5,5h4v4H5V5zM3,21h8v-8H3v8zM5,15h4v4H5v-4zM13,3v8h8V3h-8zM19,9h-4V5h4v4zM13,13h2v2h-2zM15,15h2v2h-2zM13,17h2v2h-2zM17,17h2v2h-2zM19,19h2v2h-2zM15,19h2v2h-2zM17,13h2v2h-2zM19,15h2v2h-2z",
        )
    }

    val PhotoCamera: ImageVector by lazy {
        icon(
            "PhotoCamera",
            "M12,15.2a3.2,3.2 0,1 0,0 -6.4a3.2,3.2 0,1 0,0 6.4zM9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        )
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    /** A video camera (24 dp), for "video call". */
    val Videocam: ImageVector by lazy {
        ImageVector.Builder(name = "Videocam", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 10.5f)
                verticalLineTo(7f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(12f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-3.5f)
                lineToRelative(4f, 4f)
                verticalLineToRelative(-11f)
                lineToRelative(-4f, 4f)
                close()
            }
            .build()
    }
}

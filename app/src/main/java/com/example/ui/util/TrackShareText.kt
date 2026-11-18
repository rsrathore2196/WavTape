package com.example.ui.util

import com.example.data.model.MusicTrack

internal fun buildTrackShareText(track: MusicTrack): String =
    "Listening to \"${track.title}\" by ${track.artist} on WavTape!\n\n" +
        "• Hi Res Lossless Audio Support\n" +
        "• Personalized Home and Recommendations according to your Mood\n" +
        "• Beautiful Liquid Glass Interface with Custom themes\n" +
        "• Synced Lyrics Support in your own language\n" +
        "• Open Source, No Ads and Free to use\n\n" +
        "Download WavTape:\nhttps://github.com/rsrathore2196/Xtreme-Player/releases/latest"

package com.opendroid.ai.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Manifest-registered receiver for the OpenDroid command widget (C1).
 * All rendering lives in [OpenDroidCommandWidget]; this is only the
 * framework entry point (metadata in `res/xml/opendroid_widget_info.xml`).
 */
class OpenDroidWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OpenDroidCommandWidget()
}

import QtQuick
import miuix.Core
import design

// Vocal/instrumental mixer popup, modelled after VolumePopup: a button opens it,
// it floats above the MiniPlayer/lyric page, and a slider writes player.vocalMix.
// 0 = instrumental, 50 = original PCM passthrough, 100 = vocals only.
Item {
    id: control
    width: 0
    height: 0

    property var anchorItem: null
    property real targetX: 0
    property real targetY: 0

    function open(target) {
        exitAnim.stop()
        if (!overlayLayer.visible) {
            popupContainer.scale = 0.8
            popupContainer.opacity = 0
        }
        var root = control
        while (root.parent) root = root.parent
        control.anchorItem = target
        overlayLayer.parent = root
        overlayLayer.anchors.fill = root
        var position = root.mapFromItem(target, 0, 0)
        control.targetX = position.x + target.width / 2
        control.targetY = root.mapFromItem(control, 0, 0).y
        mixSlider.value = player.vocalMix
        overlayLayer.visible = true
        overlayLayer.forceActiveFocus()
        player.refreshVocalState()
        enterAnim.restart()
    }

    function close() {
        if (!overlayLayer.visible || exitAnim.running) return
        enterAnim.stop()
        exitAnim.restart()
    }

    function handleBack() {
        if (!overlayLayer.visible) return false
        var anchor = control.anchorItem
        control.close()
        if (anchor) anchor.forceActiveFocus()
        return true
    }

    Item {
        id: overlayLayer
        visible: false
        z: 99999
        Keys.onEscapePressed: { control.handleBack(); event.accepted = true }

        // Separation state changes on the AUDIO thread a block after the slider
        // moves, so a single read at open time would latch a stale value (the popup
        // kept saying "separating" even back at centre). Poll while visible.
        Timer {
            id: statePoll
            interval: 300
            repeat: true
            running: overlayLayer.visible
            onTriggered: player.refreshVocalState()
        }

        MouseArea { anchors.fill: parent; onPressed: control.close() }

        Rectangle {
            id: popupContainer
            objectName: "vocalMixPopup"
            width: Math.max(0, Math.min(320, overlayLayer.width - 32))
            height: 132
            x: Math.max(16, Math.min(control.targetX - width / 2, overlayLayer.width - width - 16))
            y: Math.max(16, Math.min(control.targetY - height - 12, overlayLayer.height - height - 16))
            radius: Ui.radiusLg
            color: Theme.color.surfaceContainerHigh
            border.width: 1
            border.color: Ui.hairline
            scale: 0.8
            opacity: 0

            ParallelAnimation {
                id: enterAnim
                NumberAnimation { target: popupContainer; property: "scale"; to: 1; duration: 200; easing.type: Easing.OutCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; to: 1; duration: 150 }
            }
            ParallelAnimation {
                id: exitAnim
                onFinished: { overlayLayer.visible = false; control.anchorItem = null }
                NumberAnimation { target: popupContainer; property: "scale"; to: 0.8; duration: 150; easing.type: Easing.InCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; to: 0; duration: 150 }
            }

            MouseArea { anchors.fill: parent }

            Text {
                id: titleText
                anchors.top: parent.top
                anchors.topMargin: Ui.space3
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                text: "人声 / 伴奏"
                color: Ui.textPrimary
                font.pixelSize: Ui.textBody
                font.weight: Ui.weightBold
            }

            Text {
                id: statusText
                anchors.top: titleText.top
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                text: !player.vocalSupported ? "不支持"
                      : (player.vocalState === 3 ? "分离中"
                      : (player.vocalState === 1 ? "准备中"
                      : (player.vocalState === 4 ? "已降级" : "原声")))
                color: player.vocalState === 4 ? Ui.danger : Ui.textSecondary
                font.pixelSize: Ui.textCaption
            }

            Slider {
                id: mixSlider
                objectName: "vocalMixSlider"
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                anchors.top: titleText.bottom
                anchors.topMargin: Ui.space3
                from: 0
                to: 100
                stepSize: 1
                value: player.vocalMix
                onValueChanged: player.setVocalMix(Math.round(value))
            }

            Row {
                anchors.top: mixSlider.bottom
                anchors.topMargin: Ui.space1
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                Text { width: parent.width / 3; text: "伴奏"; color: Ui.textTertiary; font.pixelSize: Ui.textCaption }
                Text { width: parent.width / 3; text: "原声"; color: Ui.textTertiary; font.pixelSize: Ui.textCaption; horizontalAlignment: Text.AlignHCenter }
                Text { width: parent.width / 3; text: "人声"; color: Ui.textTertiary; font.pixelSize: Ui.textCaption; horizontalAlignment: Text.AlignRight }
            }
        }
    }
}

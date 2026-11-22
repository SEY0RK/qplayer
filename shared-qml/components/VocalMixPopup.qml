import QtQuick
import miuix.Core
import design

// Vocal separation popup, modelled after VolumePopup.
//
// A master SWITCH decides whether separation runs at all: off = untouched
// original PCM. When on, the slider spans instrumental -> vocals. Centre happens
// to reproduce the original mix, but it is NOT a required detent any more —
// expecting a finger to land exactly on 50% was the wrong interaction.
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

    function stateLabel() {
        if (!player.vocalSupported) return "不支持"
        if (!player.vocalOn) return "已关闭"
        var s = player.vocalState
        if (s === 3) return "分离中"
        if (s === 1) return "准备中"
        if (s === 4) return "已降级"
        return "就绪"
    }

    Item {
        id: overlayLayer
        visible: false
        z: 99999
        Keys.onEscapePressed: { control.handleBack(); event.accepted = true }

        // Separation state changes on the AUDIO thread a block after the switch or
        // slider moves, so poll while visible instead of latching a stale value.
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
            width: Math.max(0, Math.min(340, overlayLayer.width - 32))
            height: 184
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
                text: "人声分离"
                color: Ui.textPrimary
                font.pixelSize: Ui.textBody
                font.weight: Ui.weightBold
            }

            Text {
                id: statusText
                anchors.verticalCenter: titleText.verticalCenter
                anchors.left: titleText.right
                anchors.leftMargin: Ui.space2
                text: control.stateLabel()
                color: player.vocalState === 4 ? Ui.danger : Ui.textSecondary
                font.pixelSize: Ui.textCaption
            }

            Switch {
                id: enableSwitch
                objectName: "vocalEnableSwitch"
                anchors.verticalCenter: titleText.verticalCenter
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                enabled: player.vocalSupported
                checked: player.vocalOn
                onCheckedChanged: {
                    if (checked !== player.vocalOn) player.setVocalOn(checked)
                }
            }

            Slider {
                id: mixSlider
                objectName: "vocalMixSlider"
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                anchors.top: titleText.bottom
                anchors.topMargin: Ui.space4
                from: 0
                to: 100
                stepSize: 1
                enabled: player.vocalOn
                opacity: enabled ? 1.0 : 0.4
                value: player.vocalMix
                onMoved: player.setVocalMix(Math.round(value))
                onEditingFinished: player.setVocalMix(Math.round(value))
            }

            Row {
                anchors.top: mixSlider.bottom
                anchors.topMargin: Ui.space1
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                Text {
                    width: parent.width / 2
                    text: "伴奏"
                    color: Ui.textTertiary
                    font.pixelSize: Ui.textCaption
                }
                Text {
                    width: parent.width / 2
                    text: "人声"
                    color: Ui.textTertiary
                    font.pixelSize: Ui.textCaption
                    horizontalAlignment: Text.AlignRight
                }
            }

            Text {
                anchors.bottom: parent.bottom
                anchors.bottomMargin: Ui.space3
                anchors.left: parent.left
                anchors.leftMargin: Ui.space4
                anchors.right: parent.right
                anchors.rightMargin: Ui.space4
                text: player.vocalOn
                      ? "拖动调节伴奏与人声比例；开启或跳转后约 1.5 秒生效"
                      : "打开开关后按比例分离；关闭时输出原声"
                color: Ui.textTertiary
                font.pixelSize: Ui.textCaption
                wrapMode: Text.WordWrap
            }
        }
    }
}

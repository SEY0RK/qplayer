import QtQuick
import miuix.Core
import design

// Device-sync controls for the local-playlist library page. Kept as one
// component so the page itself only owns the toolbar button.
Item {
    id: control

    property string targetText: ""
    property bool dialogOpen: false

    ListModel { id: peers }

    function open() { dialog.open() }

    function reloadPeers() {
        var list
        try { list = JSON.parse(player.deviceSyncDevicesJson || "[]") }
        catch (e) { list = [] }
        peers.clear()
        for (var i = 0; i < list.length; ++i) {
            var p = list[i]
            peers.append({
                deviceId: p.deviceId || "",
                deviceName: p.deviceName || "未知设备",
                host: p.host || "",
                port: p.port || 0
            })
        }
    }

    Dialog {
        id: dialog
        objectName: "deviceSyncDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "sync"
        title: "设备同步"
        showAcceptButton: false
        rejectText: "关闭"
        onOpenedChanged: control.dialogOpen = opened

        Column {
            width: dialog.contentWidth
            spacing: Ui.space3

            Row {
                width: parent.width
                spacing: Ui.space2
                Button {
                    objectName: "deviceSyncToggle"
                    type: "filledTonal"
                    text: player.deviceSyncRunning ? "停止接收" : "开启接收"
                    onClicked: {
                        if (player.deviceSyncRunning) player.stopDeviceSync()
                        else player.startDeviceSync()
                    }
                }
                Text {
                    width: parent.width - parent.spacing - 96
                    text: player.deviceSyncRunning
                        ? ("接收中 · " + player.deviceSyncLocalAddress
                           + ":" + player.deviceSyncPort)
                        : "仅同步本地歌单；平台歌单靠各自账号同步"
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCallout
                    elide: Text.ElideRight
                    maximumLineCount: 1
                }
            }

            Text {
                text: "附近设备"
                color: Ui.textSecondary
                font.pixelSize: Ui.textCallout
            }

            Repeater {
                model: peers
                delegate: Button {
                    objectName: "deviceSyncPeer"
                    width: parent.width
                    type: "standard"
                    text: (model.deviceName || "设备") + " · "
                          + model.host + ":" + model.port
                    onClicked: player.sendLocalPlaylistsToDevice(model.deviceId)
                }
            }

            Text {
                visible: peers.count === 0
                text: player.deviceSyncRunning
                    ? "未发现设备；也可在下方手动输入地址"
                    : "开启接收后才能发现附近设备"
                color: Ui.textSecondary
                font.pixelSize: Ui.textCallout
            }

            TextField {
                objectName: "deviceSyncTarget"
                type: "outlined"
                label: "接收方地址 (例 192.168.1.5:39890)"
                leadingIcon: "link"
                width: parent.width
                height: implicitHeight
                text: control.targetText
                onTextChanged: control.targetText = text
            }

            Row {
                width: parent.width
                spacing: Ui.space2
                Button {
                    objectName: "deviceSyncTestButton"
                    type: "standard"
                    width: (parent.width - parent.spacing) / 2
                    text: "测试连接"
                    enabled: player.deviceSyncPhase === "idle"
                             || player.deviceSyncPhase === "done"
                             || player.deviceSyncPhase === "error"
                    onClicked: player.testDeviceSyncConnection(control.targetText)
                }
                Button {
                    objectName: "deviceSyncSendButton"
                    type: "filled"
                    width: (parent.width - parent.spacing) / 2
                    text: "推送全部"
                    enabled: player.deviceSyncPhase === "idle"
                             || player.deviceSyncPhase === "done"
                    onClicked: player.sendLocalPlaylistsTo(control.targetText)
                }
            }

            Rectangle {
                width: parent.width
                height: 4
                radius: Ui.radiusFull
                color: Ui.surfaceRaised
                Rectangle {
                    width: parent.width * player.deviceSyncProgress / 100
                    height: parent.height
                    radius: Ui.radiusFull
                    color: Ui.accent
                }
            }

            Text {
                width: parent.width
                text: {
                    var phase = player.deviceSyncPhase
                    if (phase === "idle") return ""
                    if (phase === "asking") return "等待对方确认…"
                    if (phase === "sending") return "正在同步… "
                        + player.deviceSyncProgress + "%"
                    return player.deviceSyncMessage
                }
                color: player.deviceSyncPhase === "error" ? Ui.danger : Ui.textSecondary
                font.pixelSize: Ui.textCallout
                wrapMode: Text.WordWrap
                elide: Text.ElideRight
                maximumLineCount: 2
            }
        }

        Timer {
            interval: 700
            repeat: true
            running: control.dialogOpen && player.deviceSyncRunning
            onTriggered: {
                player.refreshDeviceSyncDevices()
                control.reloadPeers()
            }
        }
    }
}

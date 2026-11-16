import QtQuick
import miuix.Core
import design

// Global receiver-side prompt for a device-sync request. Lives next to the
// other app dialogs so the receiver sees it no matter which page is open.
Item {
    id: control

    Dialog {
        id: prompt
        objectName: "deviceSyncPromptDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "sync"
        title: "来自其他设备的同步请求"
        text: (player.deviceSyncRequestFrom || "另一台设备") + " 想要推送本地歌单："
              + player.deviceSyncRequestCount + " 个歌单 / "
              + player.deviceSyncRequestTracks + " 首歌曲。"
        acceptText: "允许并合并"
        rejectText: "拒绝"
        onAccepted: player.approveDeviceSync()
        onRejected: player.rejectDeviceSync()
    }

    Timer {
        interval: 700
        repeat: true
        running: player.deviceSyncRunning
        onTriggered: {
            player.refreshDeviceSyncRequest()
            if (player.deviceSyncRequestFrom !== "" && !prompt.opened) prompt.open()
            else if (player.deviceSyncRequestFrom === "" && prompt.opened) prompt.close()
        }
    }
}

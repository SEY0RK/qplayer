import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."

// The content of one settings category: the catalog's groups plus the few
// host-owned blocks (credential protection on About, the plugin lists on
// Plugins). Every block is a direct child of this one ColumnLayout, and every
// block positions its own internals explicitly (no nested fillWidth chains).
ColumnLayout {
    id: panel
    property string category: ""
    property var groups: []
    // Wide master-detail shows the category name as the panel's large title.
    property bool showTitle: false

    property bool pluginsTab: panel.category === "plugins"
    property var installedPlugins: player.sourcePlugins || []
    property var availablePlugins: {
        var out = []
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (!rows[i].installed) out.push(rows[i])
        }
        return out
    }
    readonly property real captionHeight: 30

    spacing: Ui.space6

    Text {
        objectName: "settingsCategoryTitle"
        Layout.fillWidth: true
        Layout.leftMargin: Ui.space1
        visible: panel.showTitle
        text: panel.category.length > 0 ? i18n.t("settings.category." + panel.category) : ""
        color: Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textTitle1
        font.weight: Ui.weightBold
    }

    // ---- About: credential protection fallback ---------------------------------
    SettingCard {
        objectName: "settingsCredentialCard"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.category === "about" && player.credentialOwnerOnlyFallback

        Item {
            id: credentialRow
            Layout.fillWidth: true
            implicitHeight: credentialFrame.implicitHeight
            SettingRowFrame {
                id: credentialFrame
                width: credentialRow.width
                height: credentialRow.height
                icon: "enhanced_encryption"
                iconColor: Ui.accent
                title: i18n.t("settings.credential.title")
                summary: i18n.t("settings.credential.desc")
                clickable: false
                controlWidth: credentialButton.width
            }
            Button {
                id: credentialButton
                x: credentialRow.width - Ui.space4 - width
                y: (credentialRow.height - height) / 2
                type: "filledTonal"
                enabled: !player.credentialProtectionBusy
                text: i18n.t(player.credentialProtectionBusy
                             ? "settings.credential.busy"
                             : "settings.credential.button")
                onClicked: player.reenableSystemCredentialProtection()
            }
        }
    }

    // ---- Plugins: installed -----------------------------------------------------
    Item {
        objectName: "settingsInstalledPlugins"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab
        implicitHeight: panel.captionHeight + installedCard.height

        SettingCaption {
            x: Ui.space4
            y: 2
            width: Math.max(0, parent.width - Ui.space8)
            text: i18n.t("plugin.installed")
        }
        SettingCard {
            id: installedCard
            y: panel.captionHeight
            width: parent.width
            height: implicitHeight

            Repeater {
                model: panel.pluginsTab ? panel.installedPlugins : null
                PluginSettingsEntry {
                    Layout.fillWidth: true
                    pluginData: modelData
                    separator: index > 0
                }
            }
            Item {
                id: noPluginsRow
                Layout.fillWidth: true
                visible: panel.installedPlugins.length === 0
                implicitHeight: noPluginsFrame.implicitHeight
                SettingRowFrame {
                    id: noPluginsFrame
                    width: noPluginsRow.width
                    height: noPluginsRow.height
                    icon: "extension_off"
                    title: i18n.t("plugin.none.title")
                    summary: i18n.t("plugin.none.desc")
                    clickable: false
                }
            }
        }
    }

    // ---- Plugins: catalog -------------------------------------------------------
    Item {
        objectName: "settingsAvailablePlugins"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab
                 && (panel.availablePlugins.length > 0 || player.pluginCatalogLoading)
        implicitHeight: panel.captionHeight + availableCard.height

        SettingCaption {
            x: Ui.space4
            y: 2
            width: Math.max(0, parent.width - Ui.space8)
            text: i18n.t("plugin.available")
        }
        SettingCard {
            id: availableCard
            y: panel.captionHeight
            width: parent.width
            height: implicitHeight

            Repeater {
                model: panel.pluginsTab ? panel.availablePlugins : null
                PluginCatalogEntry {
                    Layout.fillWidth: true
                    pluginData: modelData
                    separator: index > 0
                }
            }
            Item {
                id: catalogLoadingRow
                Layout.fillWidth: true
                visible: player.pluginCatalogLoading
                implicitHeight: 56
                LoadingIndicator {
                    x: Ui.space4
                    y: (catalogLoadingRow.height - height) / 2
                    running: player.pluginCatalogLoading && catalogLoadingRow.visible
                }
                Text {
                    x: Ui.space4 + Ui.iconSize + 14
                    y: (catalogLoadingRow.height - implicitHeight) / 2
                    width: Math.max(0, catalogLoadingRow.width - x - Ui.space4)
                    text: i18n.t("plugin.catalog.loading")
                    color: Ui.textSecondary
                    elide: Text.ElideRight
                    font.pixelSize: Ui.textCallout
                }
            }
        }
    }

    // ---- Plugins: import / refresh ----------------------------------------------
    SettingCard {
        objectName: "settingsPluginActions"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab

        Item {
            id: importRow
            Layout.fillWidth: true
            implicitHeight: importFrame.implicitHeight
            SettingRowFrame {
                id: importFrame
                width: importRow.width
                height: importRow.height
                icon: "upload_file"
                title: i18n.t("plugin.catalog.import")
                summary: i18n.t("plugin.catalog.desc")
                indicator: "chevron_right"
                rowEnabled: !player.pluginInstallBusy
                onClicked: player.requestPluginImport()
            }
        }
        Item {
            id: refreshRow
            Layout.fillWidth: true
            implicitHeight: refreshFrame.implicitHeight
            SettingRowFrame {
                id: refreshFrame
                width: refreshRow.width
                height: refreshRow.height
                icon: "refresh"
                title: i18n.t("plugin.catalog.refresh")
                separator: true
                indicator: "chevron_right"
                rowEnabled: !player.pluginCatalogLoading && !player.pluginInstallBusy
                onClicked: player.refreshPluginCatalog()
            }
        }
    }

    // ---- Device sync (local playlists) ----------------------------------------
    ListModel { id: syncDevicesModel }

    function reloadSyncDevices() {
        var peers
        try { peers = JSON.parse(player.deviceSyncDevicesJson || "[]") }
        catch (e) { peers = [] }
        syncDevicesModel.clear()
        for (var i = 0; i < peers.length; ++i) {
            var p = peers[i]
            syncDevicesModel.append({
                deviceId: p.deviceId || "",
                deviceName: p.deviceName || "未知设备",
                host: p.host || "",
                port: p.port || 0,
                platform: p.platform || ""
            })
        }
    }

    SettingCard {
        objectName: "settingsDeviceSyncCard"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.category === "about"

        Item {
            id: syncHead
            Layout.fillWidth: true
            implicitHeight: syncHeadFrame.implicitHeight
            SettingRowFrame {
                id: syncHeadFrame
                width: syncHead.width
                height: syncHead.height
                icon: "sync"
                iconColor: Ui.accent
                title: "本地歌单设备同步"
                summary: player.deviceSyncRunning
                    ? ("接收中 · " + player.deviceSyncPort + " · ID " + player.deviceSyncId)
                    : "仅同步本地歌单；平台歌单靠各自账号同步"
                clickable: false
                controlWidth: syncToggle.width
            }
            Button {
                id: syncToggle
                objectName: "settingsDeviceSyncToggle"
                x: syncHead.width - Ui.space4 - width
                y: (syncHead.height - height) / 2
                type: "filledTonal"
                text: player.deviceSyncRunning ? "停止" : "开启接收"
                onClicked: {
                    if (player.deviceSyncRunning) player.stopDeviceSync()
                    else player.startDeviceSync()
                }
            }
        }

        Item {
            objectName: "settingsDeviceSyncSend"
            Layout.fillWidth: true
            implicitHeight: 52
            TextField {
                id: syncTarget
                objectName: "settingsDeviceSyncTarget"
                x: Ui.space4
                y: 6
                width: parent.width - Ui.space4 - syncSendButton.width - Ui.space4
                label: "接收方地址 (例 192.168.1.5:39890)"
                leadingIcon: "link"
            }
            Button {
                id: syncSendButton
                objectName: "settingsDeviceSyncSendButton"
                x: parent.width - Ui.space4 - width
                y: 6
                type: "filled"
                text: "推送全部"
                enabled: player.deviceSyncPhase === "idle" || player.deviceSyncPhase === "done"
                onClicked: player.sendLocalPlaylistsTo(syncTarget.text)
            }
        }

        Item {
            objectName: "settingsDeviceSyncNearby"
            Layout.fillWidth: true
            implicitHeight: nearbyColumn.implicitHeight
            Column {
                id: nearbyColumn
                width: parent.width
                spacing: Ui.space2
                Text {
                    text: "附近设备（需先开启接收）"
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCallout
                }
                Repeater {
                    model: syncDevicesModel
                    delegate: Button {
                        objectName: "settingsDeviceSyncPeer"
                        width: nearbyColumn.width
                        type: "standard"
                        text: (model.deviceName || "设备") + " · "
                              + model.host + ":" + model.port
                        onClicked: player.sendLocalPlaylistsToDevice(model.deviceId)
                    }
                }
                Text {
                    visible: syncDevicesModel.count === 0
                    text: "未发现设备；也可在上方手动输入地址"
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCallout
                }
            }
        }

        Item {
            objectName: "settingsDeviceSyncStatus"
            Layout.fillWidth: true
            implicitHeight: 30
            Text {
                x: Ui.space4
                y: 6
                width: parent.width - 2 * Ui.space4
                text: {
                    var phase = player.deviceSyncPhase
                    if (phase === "idle") return ""
                    if (phase === "sending" || phase === "asking") return "正在联系对方…"
                    if (phase === "done" || phase === "error") return player.deviceSyncMessage
                    return player.deviceSyncMessage
                }
                color: player.deviceSyncPhase === "error" ? Ui.danger : Ui.textSecondary
                font.pixelSize: Ui.textCallout
                elide: Text.ElideRight
                maximumLineCount: 1
            }
        }
    }

    // A pending receiver-side request: exactly one tap and it is applied.
    Dialog {
        id: deviceSyncConfirm
        objectName: "deviceSyncConfirm"
        title: "来自其他设备的同步请求"
        Item {
            implicitWidth: 360
            implicitHeight: 132
            height: 132
            Text {
                x: Ui.space4
                y: Ui.space3
                width: parent.width - 2 * Ui.space4
                text: (player.deviceSyncRequestFrom || "另一台设备") + " 想要推送本地歌单："
                      + player.deviceSyncRequestCount + " 个歌单 / "
                      + player.deviceSyncRequestTracks + " 首歌曲。"
                color: Ui.textPrimary
                font.pixelSize: Ui.textCallout
                wrapMode: Text.WordWrap
            }
            Row {
                x: parent.width - Ui.space4 - childrenRect.width
                y: parent.height - 46
                spacing: Ui.space2
                Button {
                    type: "standard"
                    text: "拒绝"
                    onClicked: { player.rejectDeviceSync(); deviceSyncConfirm.close() }
                }
                Button {
                    objectName: "deviceSyncConfirmApprove"
                    type: "filled"
                    text: "允许并合并"
                    onClicked: { player.approveDeviceSync(); deviceSyncConfirm.close() }
                }
            }
        }
    }

    Timer {
        objectName: "deviceSyncRequestPoller"
        interval: 700
        repeat: true
        running: player.deviceSyncRunning
        onTriggered: {
            player.refreshDeviceSyncRequest()
            player.refreshDeviceSyncDevices()
            reloadSyncDevices()
            if (player.deviceSyncRequestFrom !== "" && !deviceSyncConfirm.opened) {
                deviceSyncConfirm.open()
            }
        }
    }

    // ---- Catalog groups -----------------------------------------------------------
    Repeater {
        model: panel.groups
        SettingGroup {
            Layout.fillWidth: true
            Layout.preferredHeight: implicitHeight
            groupData: modelData
        }
    }
}

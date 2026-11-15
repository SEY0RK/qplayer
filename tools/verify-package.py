# -*- coding: utf-8 -*-
"""交付前核对：lib 逐项 md5、主 jar 内 QML 与源码一致、新功能标记存在。"""
import zipfile
import hashlib
import os

W = r"D:\seyork\Desktop\workbuddy\qplayer"
R = r"D:\seyork\Desktop\workbuddy\repos\qplayer"
pkg = os.path.join(W, "dist-portable", "QPlayer")
app = os.path.join(pkg, "app")


def md5s(d):
    out = {}
    for f in sorted(os.listdir(d)):
        if f.endswith(".jar"):
            with open(os.path.join(d, f), "rb") as fh:
                out[f] = hashlib.md5(fh.read()).hexdigest()
    return out


print("=== 1. app/lib 逐项 md5 vs 构建产物 ===")
a = md5s(os.path.join(app, "lib"))
b = md5s(os.path.join(R, "desktop-host", "target", "app", "lib"))
print("  包内 jar:", len(a), " 构建 jar:", len(b))
bad = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
print("  不一致:", bad if bad else "无（完全一致）")

print()
print("=== 2. 主 jar 内 QML 与源码逐字节一致 ===")
jar = zipfile.ZipFile(os.path.join(app, "qplayer.jar"))
names = set(jar.namelist())
for arc, src in [
    ("pages/SearchPage.qml", os.path.join(R, "shared-qml", "pages", "SearchPage.qml")),
    ("miuix/Core/ViewportScrollBar.qml", os.path.join(R, "shared-qml", "miuix", "Core", "ViewportScrollBar.qml")),
    ("components/PlaylistListRow.qml", os.path.join(R, "shared-qml", "components", "PlaylistListRow.qml")),
]:
    if arc not in names:
        print("  [缺] 包内没有", arc)
        continue
    if not os.path.isfile(src):
        print("  [源码缺]", src)
        continue
    x = jar.read(arc).replace(b"\r\n", b"\n")
    y = open(src, "rb").read().replace(b"\r\n", b"\n")
    print("  [%s] %s" % ("一致" if x == y else "不一致", arc))

print()
print("=== 3. 新功能标记 ===")
qn = set(jar.namelist())
print("  qplayer.jar 含 AacPcmSource.class :",
      "dev/t1m3/qplayer/desktop/audio/AacPcmSource.class" in qn)
print("  qplayer.jar 含 Mp4AacTrack.class  :",
      "dev/t1m3/qplayer/desktop/audio/Mp4AacTrack.class" in qn)
print("  qplayer.jar 残留 Mp4AacPcmSource  :",
      "dev/t1m3/qplayer/desktop/audio/Mp4AacPcmSource.class" in qn)

core = zipfile.ZipFile(os.path.join(app, "lib", "player-core-0.1.0-SNAPSHOT.jar"))
cn = set(core.namelist())
print("  player-core 含 PlayerController.class:", "dev/t1m3/qplayer/bridge/PlayerController.class" in cn)
lang = core.read("lang/zh_CN.properties").decode("utf-8", "replace")
print("  lang 含 search.scope.all:", "search.scope.all" in lang)
print("  lang 含 全部音源:", "全部音源" in lang)
en = core.read("lang/en_US.properties").decode("utf-8", "replace")
print("  en 含 All sources:", "All sources" in en)

print()
print("=== 4. SearchPage.qml 里音源筛选条已改为绝对定位 ===")
qml = jar.read("pages/SearchPage.qml").decode("utf-8", "replace")
# 取 objectName 之后足够长的片段：源码里紧跟数行注释才到 x/y/width/height，
# 窗口太短（原为 260）会漏掉定位属性，造成假警报。
seg = qml.split('objectName: "searchSourceBar"')[1][:800] if 'searchSourceBar' in qml else ""
print("  含 searchSourceBar:", 'searchSourceBar' in qml)
print("  已用 x: 16 绝对定位:", "x: 16" in seg)
print("  已无 Layout.fillWidth 残留:", "Layout.fillWidth" not in seg)

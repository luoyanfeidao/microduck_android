"""Vector diagrams derived from this workspace's build and runtime code."""
import math
from reportlab.lib import colors
from reportlab.graphics.shapes import Drawing, Rect, String, Line, Polygon
from reportlab.graphics import renderSVG


def make_diagrams(width, out):
    navy, teal = colors.HexColor('#17344b'), colors.HexColor('#087f8c')
    light, border = colors.HexColor('#edf4f7'), colors.HexColor('#c6d9e2')
    result = {}
    folder = out / 'diagrams'
    folder.mkdir(exist_ok=True)

    def new(name, height):
        d = Drawing(width, height)
        result[name] = d
        return d

    def box(d, x, y, w, h, title, subtitle='', color=light):
        d.add(Rect(x, y, w, h, rx=6, ry=6, fillColor=color, strokeColor=border, strokeWidth=.8))
        lines = [title] + subtitle.split('\n') if subtitle else [title]
        top = y + h / 2 + (len(lines) - 1) * 7 - 3
        for i, label in enumerate(lines):
            d.add(String(x + w / 2, top - i * 14, label, fontName='CN',
                fontSize=10 if i == 0 else 8, fillColor=navy, textAnchor='middle'))

    def arrow(d, x1, y1, x2, y2, label='', dy=7):
        d.add(Line(x1, y1, x2, y2, strokeColor=teal, strokeWidth=1.2))
        a = math.atan2(y2 - y1, x2 - x1)
        d.add(Polygon([x2, y2, x2 - 6 * math.cos(a - .45), y2 - 6 * math.sin(a - .45),
            x2 - 6 * math.cos(a + .45), y2 - 6 * math.sin(a + .45)], fillColor=teal, strokeColor=teal))
        if label:
            d.add(String((x1 + x2) / 2, (y1 + y2) / 2 + dy, label,
                fontName='CN', fontSize=8, fillColor=teal, textAnchor='middle'))

    def label(d, x, y, text):
        d.add(String(x, y, text, fontName='CN', fontSize=8.5, fillColor=teal, textAnchor='middle'))

    d = new('reuse', 196)
    box(d, 0, 136, 145, 52, 'microduck_rl', 'MJCF / STL\n训练与 ONNX 导出')
    box(d, 183, 136, 145, 52, 'microduck', 'Rust 控制服务\n本地 policies/*.onnx')
    box(d, 366, 136, 143, 52, 'MuJoCo 上游', '3.10.0 C/C++ 源码')
    box(d, 0, 38, 145, 50, 'prepare_assets.py', '复制 XML / STL / ONNX')
    box(d, 183, 38, 145, 50, 'Android Java', 'ORT / 控制 / GLES / HTTP')
    box(d, 366, 38, 143, 50, 'Android NDK', 'MuJoCo + JNI 两份 .so')
    arrow(d, 72, 136, 72, 89, '模型')
    arrow(d, 255, 136, 112, 89, '权重')
    arrow(d, 437, 136, 437, 89, '源码交叉编译')
    arrow(d, 145, 63, 182, 63)
    arrow(d, 366, 63, 329, 63)
    label(d, 255, 12, '复用文件与控制约定；Rust / Python 服务不进入 APK')

    d = new('build', 286)
    box(d, 0, 223, 145, 52, '资源分支', 'RL scene.xml + includes\nRust目录本地 ONNX')
    box(d, 183, 223, 145, 52, 'Java 分支', 'Android 源码\nORT + WebRTC AAR')
    box(d, 366, 223, 143, 52, 'Native 分支', 'MuJoCo 源码\nJNI + NDK25补丁')
    box(d, 0, 134, 145, 52, 'preBuild 前置任务', 'prepareModelAssets\n复制 + 摘要清单')
    box(d, 183, 134, 145, 52, 'Gradle / AGP', 'Java → DEX\nAAR 合并')
    box(d, 366, 134, 143, 52, 'CMake / Clang', 'Android arm64-v8a\nlibmujoco / libmicroduck_m1')
    arrow(d, 72, 223, 72, 187)
    arrow(d, 255, 223, 255, 187)
    arrow(d, 437, 223, 437, 187)
    box(d, 145, 41, 220, 55, 'APK 打包', 'DEX + assets + arm64 .so\n由用户 Studio 构建/安装')
    arrow(d, 72, 134, 180, 97)
    arrow(d, 255, 134, 255, 97)
    arrow(d, 437, 134, 330, 97)
    label(d, 255, 13, '三条分支汇合；不承诺它们在 Gradle 中固定先后或固定并行')

    d = new('model', 237)
    for x, title, sub in [(0, 'Onshape CAD', '装配 / 质量 / 几何'),
                           (183, 'JSON + 导出器', '选择零件 / 后处理规则'),
                           (366, 'robot XML + STL', '已生成模型文件')]:
        box(d, x, 180, 143, 48, title, sub)
    arrow(d, 143, 204, 182, 204)
    arrow(d, 326, 204, 365, 204)
    box(d, 366, 93, 143, 49, 'scene.xml', '模型 + 地面 + keyframes')
    arrow(d, 437, 180, 437, 143)
    box(d, 183, 93, 143, 49, 'Android 资源收集', '递归 include / 文件依赖')
    arrow(d, 366, 117, 327, 117)
    box(d, 0, 93, 143, 49, 'assets/model', '保持相对路径 / SHA-256')
    arrow(d, 183, 117, 144, 117)
    box(d, 0, 9, 143, 49, 'MuJoCo mj_loadXML', 'XML → 内存 mjModel')
    arrow(d, 71, 93, 71, 59)
    label(d, 333, 50, 'Android 构建只消费现成 XML / STL')
    label(d, 333, 29, '没有联网访问 CAD，也不执行 JSON → XML 导出')

    d = new('runtime', 338)
    box(d, 0, 275, 145, 50, 'UI / 外部预览GL', '按钮 / Activity\n可关闭GLSurfaceView')
    box(d, 366, 275, 143, 50, 'HTTP 收发线程', '命令 / 状态 / RTC信令')
    box(d, 183, 185, 145, 64, '单仿真工作线程', '20ms控制 / ORT + JNI\nMuJoCo唯一访问者')
    arrow(d, 145, 300, 215, 250)
    arrow(d, 366, 300, 295, 250)
    box(d, 0, 99, 145, 54, 'JNI / MuJoCo', '61D / 14D / 4物理步\nmjModel / mjData')
    arrow(d, 183, 205, 120, 154)
    box(d, 183, 15, 145, 53, '报告线程', 'JSON / 文件 / logcat\n最新状态缓存')
    arrow(d, 255, 185, 255, 69)
    box(d, 366, 99, 143, 54, '独立相机GL线程', '自己的EGL / Surface\n只读CPU快照')
    arrow(d, 328, 205, 393, 154, '30Hz快照')
    box(d, 366, 15, 143, 53, 'WebRTC / helper', '纹理 / peer / 硬件编码\n独立native线程')
    arrow(d, 437, 99, 437, 69)
    label(d, 72, 42, 'GL / HTTP / 报告')
    label(d, 72, 22, '不访问原生模型句柄')

    d = new('camera', 348)
    box(d, 0, 279, 145, 50, 'MuJoCo 场景', 'head_camera世界姿态\n局部X转180°')
    box(d, 183, 279, 145, 50, 'CPU快照 / 网格', '预览和相机分别上传\nGPU对象独立')
    box(d, 366, 279, 143, 50, '独立相机GLES3', '640×360 FBO\n背面剔除 / 无覆盖标注')
    arrow(d, 145, 304, 182, 304)
    arrow(d, 328, 304, 365, 304)
    box(d, 366, 183, 143, 54, 'EGL window Surface', 'blit + swap / 无View\nSurfaceTextureHelper')
    arrow(d, 437, 279, 437, 238)
    box(d, 183, 183, 145, 54, 'WebRTC硬件编码', 'VideoSource / VideoTrack\nMediaCodec / H.264优先')
    arrow(d, 366, 210, 329, 210)
    box(d, 0, 183, 145, 54, '媒体连接', 'ICE / DTLS-SRTP\n无STUN/TURN / 无音频')
    arrow(d, 183, 210, 146, 210)
    box(d, 0, 88, 145, 54, '浏览器video', 'RTC旋转90°\n显示实际codec与解码帧')
    arrow(d, 72, 183, 72, 143)
    box(d, 366, 88, 143, 54, 'JPEG兼容路径', '仅按需RGBA读回\n质量70 / 目标10FPS')
    arrow(d, 508, 279, 508, 143)
    box(d, 183, 88, 145, 54, 'HTTP /api/frame', 'Bearer / 最新一帧\nimg CSS旋转90°')
    arrow(d, 366, 115, 329, 115)
    label(d, 255, 48, 'HTTP /api/rtc 交换信令；HTTP命令与3秒控制心跳保持')
    label(d, 255, 22, '关闭手机3D可继续相机；M8新增设备功能待用户Studio验证')


    d = new('desktop', 289)
    box(d, 0, 219, 145, 54, 'microduck / Cargo', '编译 robotd / robotctl\n相机需要 mediad')
    box(d, 366, 219, 143, 54, 'microduck_rl / Python', 'duck-body\nMuJoCo 身体与传感器')
    box(d, 0, 126, 145, 54, 'Rust robotd', 'obs / ORT / 控制 / Safety')
    box(d, 366, 126, 143, 54, 'body_server.py', '位置伺服 / 地面接触\nJSON read / write')
    arrow(d, 72, 219, 72, 181)
    arrow(d, 437, 219, 437, 181)
    arrow(d, 145, 167, 365, 167, 'TCP目标')
    arrow(d, 366, 141, 146, 141, 'TCP传感器', dy=-15)
    box(d, 0, 18, 145, 57, 'Rust mediad', 'GStreamer / WebRTC\n浏览器实时相机')
    box(d, 366, 18, 143, 57, 'Python camera.py', 'MuJoCo Renderer\nUYVY相机帧')
    arrow(d, 437, 126, 437, 76)
    arrow(d, 366, 46, 146, 46, 'TCP相机帧')
    label(d, 255, 100, '桌面复用真实 Rust 服务；Android 已移植控制到 Java/JNI')

    for name, drawing in result.items():
        renderSVG.drawToFile(drawing, str(folder / f'{name}.svg'))
    return result

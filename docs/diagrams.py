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
    box(d, 183, 223, 145, 52, 'Java 分支', 'Android 源码\nORT Android 1.20.0 AAR')
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

    d = new('runtime', 298)
    box(d, 0, 235, 145, 50, 'UI 主线程', '按钮 / Activity / 显示')
    box(d, 366, 235, 143, 50, 'HTTP 收发线程', '命令 / 状态 / JPEG')
    box(d, 183, 155, 145, 66, '单仿真工作线程', '20ms 控制周期\nORT + JNI 唯一访问者')
    arrow(d, 145, 250, 215, 222, '本地投递')
    arrow(d, 366, 250, 295, 222, 'Mailbox')
    box(d, 0, 81, 145, 54, 'JNI / MuJoCo', '观测 → action → 4物理步\nmjModel / mjData')
    arrow(d, 183, 176, 120, 136)
    box(d, 183, 15, 145, 53, '报告线程', 'JSON / 文件 / logcat\n最新状态缓存')
    arrow(d, 255, 155, 255, 69, '约250ms捕获')
    box(d, 366, 81, 143, 54, 'GLES 渲染线程', '网格 + 不可变快照\n外部视角 / 相机FBO')
    arrow(d, 328, 176, 393, 136, '30Hz快照')
    d.add(Line(328, 42, 345, 42, strokeColor=teal, strokeWidth=1.2))
    d.add(Line(345, 42, 345, 260, strokeColor=teal, strokeWidth=1.2))
    arrow(d, 345, 260, 365, 260)
    label(d, 437, 293, '只读报告缓存，不触发JNI')
    label(d, 72, 34, 'GL / HTTP / 报告不访问原生句柄')

    d = new('camera', 242)
    box(d, 0, 179, 145, 50, 'MuJoCo 状态', 'head_camera 世界姿态\n局部X转180°修正')
    box(d, 183, 179, 145, 50, '场景快照 v2', '相机 / FOV / 裁剪\n与手机观察视角独立')
    box(d, 366, 179, 143, 50, 'GLES 相机 FBO', '640×360 / 背面剔除\nglReadPixels RGBA')
    arrow(d, 145, 204, 182, 204)
    arrow(d, 328, 204, 365, 204)
    box(d, 366, 87, 143, 50, 'JPEG 编码线程', '翻转行 / RGBA→ARGB\n质量70 / 目标10FPS')
    arrow(d, 437, 179, 437, 138)
    box(d, 183, 87, 145, 50, '最新 JPEG 缓存', '最多一个在途任务\n按需采集')
    arrow(d, 366, 112, 329, 112)
    box(d, 0, 87, 145, 50, 'HTTP /api/frame', 'Bearer 访问码\n请求独立于控制链')
    arrow(d, 183, 112, 146, 112)
    box(d, 0, 6, 145, 49, '控制端浏览器', '安装方向顺时针90°')
    arrow(d, 72, 87, 72, 56)
    label(d, 333, 40, '机器人相机重新渲染场景')
    label(d, 333, 19, '不是复制手机模拟窗口，也不是手机实体摄像头')

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

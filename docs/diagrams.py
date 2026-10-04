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
    box(d, 183, 136, 145, 52, 'microduck', 'Rust 控制源码\n本地 policies/*.onnx')
    box(d, 366, 136, 143, 52, 'MuJoCo 上游', '3.10.0 C/C++ 源码')
    box(d, 0, 38, 145, 50, 'prepare_assets.py', '复制 XML / STL / ONNX')
    box(d, 183, 38, 145, 50, 'Android Java', 'ORT / UI / GLES / HTTP')
    box(d, 366, 38, 143, 50, 'Android NDK', 'Rust + MuJoCo + JNI')
    arrow(d, 72, 136, 72, 89, '模型')
    arrow(d, 255, 136, 112, 89, '权重')
    arrow(d, 437, 136, 437, 89, 'C/C++')
    arrow(d, 285, 136, 397, 89, 'Rust源码')
    arrow(d, 145, 63, 182, 63)
    arrow(d, 366, 63, 329, 63)
    label(d, 255, 12, 'M10：Rust核心进入APK；Python训练环境与桌面daemon留在主机')

    d = new('build', 286)
    box(d, 0, 223, 145, 52, '资源分支', 'RL scene.xml + includes\nRust目录本地 ONNX')
    box(d, 183, 223, 145, 52, 'Java 分支', 'Android 源码\nORT + WebRTC AAR')
    box(d, 366, 223, 143, 52, 'Native 分支', 'MuJoCo / C++ / Rust\nJNI + BAM + 控制核心')
    box(d, 0, 134, 145, 52, 'preBuild 前置任务', 'prepareModelAssets\n复制 + 摘要清单')
    box(d, 183, 134, 145, 52, 'Gradle / AGP', 'Java → DEX\nAAR 合并')
    box(d, 366, 134, 143, 52, 'CMake / NDK / Cargo', 'Android arm64-v8a\nlibmujoco / libmicroduck_m1')
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
    box(d, 183, 185, 145, 64, '单仿真工作线程', '20ms / Rust + ORT + JNI\n所有控制状态属于此线程')
    arrow(d, 145, 300, 215, 250)
    arrow(d, 366, 300, 295, 250)
    box(d, 0, 99, 145, 54, 'JNI / MuJoCo', 'Rust目标 / BAM / 四步\nmjModel / mjData')
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
    label(d, 255, 22, '关闭手机3D可继续相机；M8用户反馈实机验证正常')


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
    label(d, 255, 100, '桌面：完整daemon；Android：内嵌Rust核心或外部SimIo接管')

    d = new('bam', 248)
    box(d, 0, 176, 145, 56, 'Rust关节目标', 'HOME + scale × raw action\nEMA / Safety / 固件kp')
    box(d, 183, 176, 145, 56, 'BAM位置环', '误差 → PWM → 电压\n反电动势 → 电机力矩')
    box(d, 366, 176, 143, 56, 'MuJoCo motor', '每5ms力矩更新\n接触与约束求解')
    arrow(d, 145, 203, 182, 203)
    arrow(d, 328, 203, 365, 203)
    box(d, 183, 65, 145, 58, 'BAM M6摩擦', '负载 / 方向 / Stribeck\n黏性阻尼 / armature')
    box(d, 366, 65, 143, 58, '关节 / 受力反馈', '位置 / 速度\n上一物理步约束力')
    arrow(d, 437, 176, 437, 124)
    arrow(d, 366, 94, 329, 94)
    arrow(d, 285, 123, 407, 175, '摩擦参数')
    label(d, 255, 30, '7.4V名义参数；动态电压下垂；训练DR与延迟尚未移植')
    label(d, 255, 10, 'XML伺服可切回对照；切换暂停并复位SIT')

    d = new('embedded', 346)
    box(d, 0, 279, 145, 53, 'MuJoCo传感器', 'pos15 / vel15 / IMU10\n40D身体样本')
    box(d, 183, 279, 145, 53, 'Rust Controller', '上游obs → 61D\n网络选择 / 技能窗口')
    box(d, 366, 279, 143, 53, 'JNI同步回调', 'PolicyRunner.rustInfer\n同一仿真worker')
    arrow(d, 145, 305, 182, 305)
    arrow(d, 328, 305, 365, 305)
    box(d, 366, 175, 143, 59, 'Android ORT 1.20', '三份FF ONNX / normalizer\n14D raw action')
    arrow(d, 437, 279, 437, 235)
    box(d, 183, 175, 145, 59, 'Rust动作与Safety', 'HOME / scale / EMA / kp\n有限值与行程约束')
    arrow(d, 366, 205, 329, 205)
    box(d, 0, 175, 145, 59, 'JNI身体目标', '15槽映射到14关节\n无重复滤波')
    arrow(d, 183, 205, 146, 205)
    box(d, 0, 71, 145, 59, 'BAM / XML / MuJoCo', '四个5ms物理步\n相机保持独立')
    arrow(d, 72, 175, 72, 131)
    label(d, 338, 103, '本地模式无需电脑 / USB / robotd进程')
    label(d, 338, 80, 'Rust以静态库链接进现有JNI .so')
    label(d, 255, 25, '推理后端保留Android ORT；控制决策与状态在Rust')

    d = new('rust_build', 253)
    box(d, 0, 184, 145, 54, 'Pollen Rust源码', 'Controller / obs / Safety\nparams / proto / kinematics')
    box(d, 183, 184, 145, 54, 'Android Rust适配', 'FF推理回调 / C ABI\nCargo.lock / 来源SHA')
    box(d, 366, 184, 143, 54, 'Studio → CMake', 'Python校验 → Cargo\naarch64-linux-android')
    arrow(d, 145, 212, 182, 212)
    arrow(d, 328, 212, 365, 212)
    box(d, 366, 82, 143, 58, 'Rust静态库 .a', 'libmicroduck_android_core\n无需桌面ORT动态库')
    arrow(d, 437, 184, 437, 141)
    box(d, 183, 82, 145, 58, 'NDK链接JNI .so', 'C++ / BAM / Rust\nMuJoCo与系统库')
    arrow(d, 366, 111, 329, 111)
    box(d, 0, 82, 145, 58, 'APK', 'DEX / assets / .so\n由用户Studio安装运行')
    arrow(d, 183, 111, 146, 111)
    label(d, 255, 33, '构建需要Cargo；运行不需要Cargo/Python/电脑')
    label(d, 255, 12, '上游文件改变需复核源文件锁与验证，不手工复制桌面二进制')

    d = new('simio', 296)
    box(d, 0, 226, 145, 55, '电脑原版robotd', 'Rust策略 / 控制 / Safety\nUnix socket命令入口')
    box(d, 183, 226, 145, 55, 'USB adb forward', '电脑17801 → 手机7801\nRemoteIo协议1')
    box(d, 366, 226, 143, 55, 'Android SimIoServer', '仅loopback / 单客户端\n有界IO→worker请求')
    arrow(d, 145, 254, 182, 254)
    arrow(d, 328, 254, 365, 254)
    box(d, 366, 122, 143, 59, 'SimIoBody / JNI', 'wire15 / mouth占位\nread / write / gain / torque')
    arrow(d, 437, 226, 437, 182)
    box(d, 183, 122, 145, 59, 'Android MuJoCo身体', 'BAM或XML\n物理步与传感器')
    arrow(d, 366, 152, 329, 152)
    box(d, 0, 122, 145, 59, '浏览器监看', '状态 / WebRTC / JPEG\n外部模式HTTP动作409')
    arrow(d, 183, 152, 146, 152)
    label(d, 255, 76, '手机本地按钮接管：关闭外部服务，再运行内嵌Rust')
    label(d, 255, 49, '断线暂停并关闭力矩；两种控制器互斥')
    label(d, 255, 22, '相机仍走Android视频链，无mediad / GStreamer依赖')

    for name, drawing in result.items():
        renderSVG.drawToFile(drawing, str(folder / f'{name}.svg'))
    return result

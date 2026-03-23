import { useRef } from 'react';
import { motion, useScroll, useTransform, useInView } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import {
  Smartphone,
  Code2,
  Package,
  Brain,
  Monitor,
  ChevronDown,
  ArrowRight,
  Zap,
  Shield,
  Terminal,
  Layers,
  MessageCircle,
} from 'lucide-react';

function FadeInSection({ children, className = '', delay = 0 }: {
  children: React.ReactNode;
  className?: string;
  delay?: number;
}) {
  const ref = useRef(null);
  const isInView = useInView(ref, { once: true, margin: '-80px' });
  return (
    <motion.div
      ref={ref}
      initial={{ opacity: 0, y: 60 }}
      animate={isInView ? { opacity: 1, y: 0 } : { opacity: 0, y: 60 }}
      transition={{ duration: 0.8, delay, ease: [0.25, 0.46, 0.45, 0.94] }}
      className={className}
    >
      {children}
    </motion.div>
  );
}


const NAV_ITEMS = [
  { id: 'features', label: '产品特性' },
  { id: 'android', label: 'Android' },
  { id: 'vscode', label: 'VSCode' },
  { id: 'sdk', label: 'SDK' },
  { id: 'console', label: '群控' },
  { id: 'contact', label: '联系我们' },
];

export default function LandingPage() {
  const navigate = useNavigate();
  const heroRef = useRef(null);
  const { scrollYProgress } = useScroll({
    target: heroRef,
    offset: ['start start', 'end start'],
  });
  const heroOpacity = useTransform(scrollYProgress, [0, 1], [1, 0]);
  const heroScale = useTransform(scrollYProgress, [0, 1], [1, 0.92]);
  const heroY = useTransform(scrollYProgress, [0, 1], ['0%', '30%']);

  const scrollTo = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="landing-page bg-[#0a0a0a] text-white min-h-screen overflow-x-hidden">
      {/* ── Navbar ── */}
      <motion.nav
        initial={{ y: -100 }}
        animate={{ y: 0 }}
        transition={{ duration: 0.8, ease: 'easeOut' }}
        className="fixed top-0 left-0 right-0 z-50 backdrop-blur-xl bg-[#0a0a0a]/70 border-b border-white/5"
      >
        <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center font-bold text-sm">
              Y
            </div>
            <span className="font-semibold text-lg tracking-tight">Yyds.Auto</span>
          </div>
          <div className="hidden md:flex items-center gap-8">
            {NAV_ITEMS.map((item) => (
              <button
                key={item.id}
                onClick={() => scrollTo(item.id)}
                className="text-sm text-white/60 hover:text-white transition-colors"
              >
                {item.label}
              </button>
            ))}
          </div>
          <button
            onClick={() => navigate('/login')}
            className="px-5 py-2 rounded-full text-sm font-medium bg-white text-black hover:bg-white/90 transition-colors"
          >
            登录控制台
          </button>
        </div>
      </motion.nav>

      {/* ── Hero Section ── */}
      <section ref={heroRef} className="relative h-[100vh] flex items-center justify-center pt-16">
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] rounded-full bg-blue-600/10 blur-[120px]" />
          <div className="absolute bottom-0 left-1/4 w-[600px] h-[600px] rounded-full bg-purple-600/8 blur-[100px]" />
        </div>
        <motion.div
          style={{ opacity: heroOpacity, scale: heroScale, y: heroY }}
          className="relative z-10 text-center max-w-5xl mx-auto px-6"
        >
          <motion.div
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.2 }}
            className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full border border-white/10 bg-white/5 text-sm text-white/70 mb-8"
          >
            <Zap size={14} className="text-blue-400" />
            Android 自动化，重新定义
          </motion.div>
          <motion.h1
            initial={{ opacity: 0, y: 40 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.4 }}
            className="text-5xl md:text-7xl lg:text-8xl font-bold tracking-tight leading-[1.1]"
          >
            <span className="bg-gradient-to-r from-white via-white to-white/60 bg-clip-text text-transparent">
              让 Android 自动化
            </span>
            <br />
            <span className="bg-gradient-to-r from-blue-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
              触手可及
            </span>
          </motion.h1>
          <motion.p
            initial={{ opacity: 0, y: 40 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.6 }}
            className="mt-8 text-lg md:text-xl text-white/50 max-w-2xl mx-auto leading-relaxed"
          >
            从单设备脚本开发到 200+ 设备群控管理，Yyds.Auto 提供完整的
            Android RPA 解决方案。Python 驱动，开箱即用。
          </motion.p>
          <motion.div
            initial={{ opacity: 0, y: 40 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.8 }}
            className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4"
          >
            <button
              onClick={() => scrollTo('features')}
              className="group px-8 py-3.5 rounded-full bg-gradient-to-r from-blue-500 to-purple-600 text-white font-medium hover:shadow-lg hover:shadow-blue-500/25 transition-all"
            >
              探索产品
              <ArrowRight size={16} className="inline ml-2 group-hover:translate-x-1 transition-transform" />
            </button>
            <button
              onClick={() => navigate('/login')}
              className="px-8 py-3.5 rounded-full border border-white/15 text-white/80 font-medium hover:bg-white/5 transition-all"
            >
              免费开始
            </button>
          </motion.div>
        </motion.div>
        <motion.div
          animate={{ y: [0, 10, 0] }}
          transition={{ duration: 2, repeat: Infinity }}
          className="absolute bottom-10 left-1/2 -translate-x-1/2"
        >
          <ChevronDown size={24} className="text-white/30" />
        </motion.div>
      </section>

      {/* ── Features Overview ── */}
      <section id="features" className="relative py-32 px-6">
        <div className="max-w-7xl mx-auto">
          <FadeInSection className="text-center mb-20">
            <p className="text-blue-400 text-sm font-medium tracking-widest uppercase mb-4">全栈解决方案</p>
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight">
              一个平台，无限可能
            </h2>
            <p className="mt-6 text-white/40 text-lg max-w-2xl mx-auto">
              覆盖开发、调试、部署、运维全流程，为 Android 自动化提供专业级工具链
            </p>
          </FadeInSection>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {[
              { icon: Smartphone, title: 'Android App', desc: 'ROOT/SHELL 权限运行，内置 CPython 3.13，支持脚本打包为独立 APK', color: 'from-green-400 to-emerald-600' },
              { icon: Code2, title: 'VSCode 插件', desc: '截图交互、控件树解析、代码补全、一键推送运行，沉浸式开发体验', color: 'from-blue-400 to-cyan-600' },
              { icon: Package, title: 'PyPI 库', desc: 'pip install yyds-auto，PC 端 Python 直接控制 Android 设备，类 uiautomator2', color: 'from-orange-400 to-red-600' },
              { icon: Brain, title: 'MCP 插件', desc: '让 AI 大模型直接操控 Android 设备，35+ 工具覆盖全部自动化场景', color: 'from-purple-400 to-pink-600' },
              { icon: Monitor, title: '设备群控', desc: '200+ 设备公网部署，实时屏幕流、批量操作、定时任务、WebRTC P2P', color: 'from-yellow-400 to-orange-600' },
              { icon: Shield, title: '脚本加密', desc: 'AES-256-GCM + 白盒密钥 + 7 层安全防护，保护你的核心业务逻辑', color: 'from-rose-400 to-red-600' },
            ].map((item, i) => (
              <FadeInSection key={item.title} delay={i * 0.1}>
                <div className="group relative p-8 rounded-2xl bg-white/[0.03] border border-white/[0.06] hover:bg-white/[0.06] hover:border-white/[0.12] transition-all duration-500">
                  <div className={`w-12 h-12 rounded-xl bg-gradient-to-br ${item.color} flex items-center justify-center mb-6 group-hover:scale-110 transition-transform duration-500`}>
                    <item.icon size={22} className="text-white" />
                  </div>
                  <h3 className="text-xl font-semibold mb-3">{item.title}</h3>
                  <p className="text-white/40 leading-relaxed">{item.desc}</p>
                </div>
              </FadeInSection>
            ))}
          </div>
        </div>
      </section>

      {/* ── Android App Section ── */}
      <section id="android" className="relative py-32 px-6">
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute top-1/2 right-0 w-[500px] h-[500px] rounded-full bg-green-600/8 blur-[120px]" />
        </div>
        <div className="max-w-7xl mx-auto">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-16 items-center">
            <FadeInSection>
              <p className="text-green-400 text-sm font-medium tracking-widest uppercase mb-4">核心引擎</p>
              <h2 className="text-4xl md:text-5xl font-bold tracking-tight leading-tight">
                Android App
                <br />
                <span className="text-white/40">强大的自动化引擎</span>
              </h2>
              <p className="mt-6 text-white/50 text-lg leading-relaxed">
                内置 CPython 3.13 嵌入式引擎，ROOT/SHELL 权限运行，三进程守护架构确保 7×24 小时稳定运行。
                支持将脚本打包为独立 APK 分发。
              </p>
              <div className="mt-8 space-y-4">
                {[
                  '嵌入式 CPython 3.13，完整 pip 生态支持',
                  '三进程三角守护，无单点故障',
                  '脚本打包为独立 APK，一键分发',
                  'AES-256-GCM 脚本加密保护',
                  '悬浮日志控制台，实时调试',
                ].map((text) => (
                  <div key={text} className="flex items-start gap-3">
                    <div className="mt-1.5 w-1.5 h-1.5 rounded-full bg-green-400 shrink-0" />
                    <span className="text-white/60">{text}</span>
                  </div>
                ))}
              </div>
            </FadeInSection>
            <FadeInSection delay={0.2}>
              <div className="relative">
                <div className="absolute inset-0 bg-gradient-to-br from-green-500/20 to-emerald-500/10 rounded-3xl blur-2xl" />
                {/* 📸 占位图: Android App 主界面截图 */}
                <div className="relative rounded-3xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-[9/16] max-w-[320px] mx-auto flex items-center justify-center">
                  <img src="/screenshots/android-app-home.png" alt="Android App 主界面" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                  <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                    <Smartphone size={48} />
                    <p className="mt-4 text-sm">android-app-home.png</p>
                  </div>
                </div>
              </div>
            </FadeInSection>
          </div>
        </div>
      </section>

      {/* ── VSCode Extension Section ── */}
      <section id="vscode" className="relative py-32 px-6">
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute top-1/3 left-0 w-[500px] h-[500px] rounded-full bg-blue-600/8 blur-[120px]" />
        </div>
        <div className="max-w-7xl mx-auto">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-16 items-center">
            <FadeInSection delay={0.2} className="order-2 lg:order-1">
              <div className="relative">
                <div className="absolute inset-0 bg-gradient-to-br from-blue-500/20 to-cyan-500/10 rounded-3xl blur-2xl" />
                {/* 📸 占位图: VSCode 插件开发界面截图 */}
                <div className="relative rounded-2xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-video flex items-center justify-center">
                  <img src="/screenshots/vscode-extension.png" alt="VSCode 插件" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                  <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                    <Code2 size={48} />
                    <p className="mt-4 text-sm">vscode-extension.png</p>
                  </div>
                </div>
              </div>
            </FadeInSection>
            <FadeInSection className="order-1 lg:order-2">
              <p className="text-blue-400 text-sm font-medium tracking-widest uppercase mb-4">开发体验</p>
              <h2 className="text-4xl md:text-5xl font-bold tracking-tight leading-tight">
                VSCode 插件
                <br />
                <span className="text-white/40">沉浸式开发</span>
              </h2>
              <p className="mt-6 text-white/50 text-lg leading-relaxed">
                在你最熟悉的编辑器中完成一切。截图交互、控件树解析、代码补全、
                一键推送运行，让 RPA 开发如丝般顺滑。
              </p>
              <div className="mt-8 grid grid-cols-2 gap-4">
                {[
                  { icon: Terminal, text: '实时日志' },
                  { icon: Layers, text: '控件树解析' },
                  { icon: Code2, text: 'API 补全' },
                  { icon: Zap, text: '一键运行' },
                ].map((item) => (
                  <div key={item.text} className="flex items-center gap-3 p-3 rounded-xl bg-white/[0.03] border border-white/[0.06]">
                    <item.icon size={18} className="text-blue-400" />
                    <span className="text-white/60 text-sm">{item.text}</span>
                  </div>
                ))}
              </div>
            </FadeInSection>
          </div>
        </div>
      </section>

      {/* ── SDK & MCP Section ── */}
      <section id="sdk" className="relative py-32 px-6">
        <div className="max-w-7xl mx-auto">
          <FadeInSection className="text-center mb-20">
            <p className="text-purple-400 text-sm font-medium tracking-widest uppercase mb-4">开发者工具</p>
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight">
              Python SDK & MCP 插件
            </h2>
            <p className="mt-6 text-white/40 text-lg max-w-2xl mx-auto">
              两种方式接入，满足不同场景需求
            </p>
          </FadeInSection>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            {/* PyPI SDK Card */}
            <FadeInSection>
              <div className="relative group h-full">
                <div className="absolute inset-0 bg-gradient-to-br from-orange-500/10 to-red-500/5 rounded-3xl blur-xl opacity-0 group-hover:opacity-100 transition-opacity duration-700" />
                <div className="relative p-10 rounded-3xl bg-white/[0.03] border border-white/[0.06] hover:border-white/[0.12] transition-all duration-500 h-full">
                  <Package size={32} className="text-orange-400 mb-6" />
                  <h3 className="text-2xl font-bold mb-4">yyds-auto (PyPI)</h3>
                  <div className="rounded-xl bg-black/50 border border-white/10 p-5 font-mono text-sm mb-6">
                    <p className="text-white/40"># 安装</p>
                    <p className="text-green-400">pip install yyds-auto</p>
                    <p className="text-white/40 mt-3"># 使用</p>
                    <p><span className="text-purple-400">import</span> yyds_auto</p>
                    <p>d = yyds_auto.<span className="text-yellow-400">connect_usb</span>()</p>
                    <p>d.<span className="text-yellow-400">screenshot</span>(<span className="text-green-400">"screen.png"</span>)</p>
                    <p>d(<span className="text-blue-400">text</span>=<span className="text-green-400">"登录"</span>).<span className="text-yellow-400">click</span>()</p>
                  </div>
                  <ul className="space-y-2 text-white/50 text-sm">
                    <li>• USB / WiFi / 局域网自动发现连接</li>
                    <li>• 对齐 uiautomator2 API 风格</li>
                    <li>• 元素选择器 + OCR + 图像匹配</li>
                    <li>• CLI 工具：截图、Shell、设备扫描</li>
                  </ul>
                </div>
              </div>
            </FadeInSection>

            {/* MCP Plugin Card */}
            <FadeInSection delay={0.15}>
              <div className="relative group h-full">
                <div className="absolute inset-0 bg-gradient-to-br from-purple-500/10 to-pink-500/5 rounded-3xl blur-xl opacity-0 group-hover:opacity-100 transition-opacity duration-700" />
                <div className="relative p-10 rounded-3xl bg-white/[0.03] border border-white/[0.06] hover:border-white/[0.12] transition-all duration-500 h-full">
                  <Brain size={32} className="text-purple-400 mb-6" />
                  <h3 className="text-2xl font-bold mb-4">MCP Server</h3>
                  <p className="text-white/50 leading-relaxed mb-6">
                    基于 Model Context Protocol，让 Claude、GPT 等 AI 大模型直接操控 Android 设备。
                    35+ 工具覆盖设备信息、触控、截图、UI 自动化、OCR、Shell 等全部场景。
                  </p>
                  {/* 📸 占位图: MCP 在 Claude/Cursor 中使用的截图 */}
                  <div className="rounded-2xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-video flex items-center justify-center">
                    <img src="/screenshots/mcp-in-action.png" alt="MCP 插件使用" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                    <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                      <Brain size={36} />
                      <p className="mt-3 text-sm">mcp-in-action.png</p>
                    </div>
                  </div>
                </div>
              </div>
            </FadeInSection>
          </div>
        </div>
      </section>

      {/* ── Console / Fleet Management Section ── */}
      <section id="console" className="relative py-32 px-6">
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-[900px] h-[500px] rounded-full bg-yellow-600/6 blur-[140px]" />
        </div>
        <div className="max-w-7xl mx-auto">
          <FadeInSection className="text-center mb-16">
            <p className="text-yellow-400 text-sm font-medium tracking-widest uppercase mb-4">规模化运营</p>
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight">
              设备群控管理
            </h2>
            <p className="mt-6 text-white/40 text-lg max-w-2xl mx-auto">
              支持 200+ 设备公网部署，实时屏幕流、批量操作、定时任务调度，
              一个控制台掌控全局
            </p>
          </FadeInSection>

          {/* 📸 占位图: 群控管理控制台全景截图 */}
          <FadeInSection>
            <div className="relative">
              <div className="absolute inset-0 bg-gradient-to-t from-[#0a0a0a] via-transparent to-transparent z-10 pointer-events-none" />
              <div className="rounded-3xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-[21/10] flex items-center justify-center">
                <img src="/screenshots/console-dashboard.png" alt="群控管理控制台" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                  <Monitor size={64} />
                  <p className="mt-4 text-sm">console-dashboard.png</p>
                </div>
              </div>
            </div>
          </FadeInSection>

          <div className="mt-16 grid grid-cols-1 md:grid-cols-3 gap-8">
            {[
              { num: '200+', label: '设备并发管理', desc: 'WebSocket 长连接，毫秒级指令下发' },
              { num: 'P2P', label: 'WebRTC 屏幕流', desc: '低延迟实时画面，5s 超时自动回退 WS' },
              { num: 'Cron', label: '定时任务调度', desc: '灵活的 Cron 表达式，自动化运维' },
            ].map((item, i) => (
              <FadeInSection key={item.label} delay={i * 0.1}>
                <div className="text-center p-8">
                  <p className="text-4xl md:text-5xl font-bold bg-gradient-to-r from-yellow-400 to-orange-400 bg-clip-text text-transparent">
                    {item.num}
                  </p>
                  <p className="mt-3 text-lg font-semibold">{item.label}</p>
                  <p className="mt-2 text-white/40 text-sm">{item.desc}</p>
                </div>
              </FadeInSection>
            ))}
          </div>

          <div className="mt-16 grid grid-cols-1 md:grid-cols-2 gap-8">
            {/* 📸 占位图: 设备详情页 */}
            <FadeInSection>
              <div className="rounded-2xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-video flex items-center justify-center relative">
                <img src="/screenshots/console-device-detail.png" alt="设备详情" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                  <Smartphone size={36} />
                  <p className="mt-3 text-sm">console-device-detail.png</p>
                </div>
              </div>
              <p className="mt-4 text-center text-white/40 text-sm">设备详情 — 实时屏幕 & 远程控制</p>
            </FadeInSection>
            {/* 📸 占位图: 在线IDE截图 */}
            <FadeInSection delay={0.15}>
              <div className="rounded-2xl overflow-hidden border border-white/10 bg-white/[0.03] aspect-video flex items-center justify-center relative">
                <img src="/screenshots/console-ide.png" alt="在线IDE" className="w-full h-full object-cover" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; (e.target as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
                <div className="hidden absolute inset-0 flex flex-col items-center justify-center text-white/20">
                  <Code2 size={36} />
                  <p className="mt-3 text-sm">console-ide.png</p>
                </div>
              </div>
              <p className="mt-4 text-center text-white/40 text-sm">在线 IDE — 云端编写 & 调试脚本</p>
            </FadeInSection>
          </div>
        </div>
      </section>

      {/* ── Pricing / CTA Section ── */}
      <section className="relative py-32 px-6">
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[700px] h-[700px] rounded-full bg-blue-600/8 blur-[140px]" />
        </div>
        <div className="max-w-4xl mx-auto text-center relative z-10">
          <FadeInSection>
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight">
              准备好开始了吗？
            </h2>
            <p className="mt-6 text-white/40 text-lg max-w-xl mx-auto">
              基础功能免费使用，企业级定制服务满足你的专属需求
            </p>
          </FadeInSection>

          <FadeInSection delay={0.2}>
            <div className="mt-12 grid grid-cols-1 md:grid-cols-2 gap-6 max-w-3xl mx-auto">
              <div className="p-8 rounded-3xl bg-white/[0.03] border border-white/[0.06] text-left">
                <p className="text-sm text-white/40 font-medium uppercase tracking-wider">开源免费</p>
                <p className="mt-3 text-3xl font-bold">¥0</p>
                <ul className="mt-6 space-y-3 text-white/50 text-sm">
                  <li className="flex items-center gap-2"><span className="text-green-400">✓</span> Android App 全功能</li>
                  <li className="flex items-center gap-2"><span className="text-green-400">✓</span> VSCode 插件</li>
                  <li className="flex items-center gap-2"><span className="text-green-400">✓</span> Python SDK (PyPI)</li>
                  <li className="flex items-center gap-2"><span className="text-green-400">✓</span> MCP 插件</li>
                  <li className="flex items-center gap-2"><span className="text-green-400">✓</span> 社区支持</li>
                </ul>
                <button onClick={() => navigate('/login')} className="mt-8 w-full py-3 rounded-full border border-white/15 text-white/80 font-medium hover:bg-white/5 transition-all">
                  免费注册
                </button>
              </div>
              <div className="p-8 rounded-3xl bg-gradient-to-br from-blue-500/10 to-purple-500/10 border border-blue-500/20 text-left relative overflow-hidden">
                <div className="absolute top-4 right-4 px-3 py-1 rounded-full bg-blue-500/20 text-blue-400 text-xs font-medium">推荐</div>
                <p className="text-sm text-blue-400 font-medium uppercase tracking-wider">企业定制</p>
                <p className="mt-3 text-3xl font-bold">联系我们</p>
                <ul className="mt-6 space-y-3 text-white/50 text-sm">
                  <li className="flex items-center gap-2"><span className="text-blue-400">✓</span> 全部免费版功能</li>
                  <li className="flex items-center gap-2"><span className="text-blue-400">✓</span> 群控管理控制台</li>
                  <li className="flex items-center gap-2"><span className="text-blue-400">✓</span> 私有化部署</li>
                  <li className="flex items-center gap-2"><span className="text-blue-400">✓</span> 定制开发</li>
                  <li className="flex items-center gap-2"><span className="text-blue-400">✓</span> 专属技术支持</li>
                </ul>
                <button onClick={() => scrollTo('contact')} className="mt-8 w-full py-3 rounded-full bg-gradient-to-r from-blue-500 to-purple-600 text-white font-medium hover:shadow-lg hover:shadow-blue-500/25 transition-all">
                  立即咨询
                </button>
              </div>
            </div>
          </FadeInSection>
        </div>
      </section>

      {/* ── Contact Section ── */}
      <section id="contact" className="relative py-32 px-6">
        <div className="max-w-4xl mx-auto text-center">
          <FadeInSection>
            <MessageCircle size={40} className="mx-auto text-blue-400 mb-6" />
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight">联系我们</h2>
            <p className="mt-6 text-white/40 text-lg">添加微信，获取技术支持或咨询企业定制方案</p>
          </FadeInSection>
          <FadeInSection delay={0.2}>
            <div className="mt-12 inline-flex flex-col items-center p-10 rounded-3xl bg-white/[0.03] border border-white/[0.06]">
              <div className="w-52 h-52 rounded-2xl overflow-hidden bg-white p-2">
                <img src="/wechat_contact.png" alt="微信二维码" className="w-full h-full object-contain" />
              </div>
              <p className="mt-6 text-lg font-medium">微信号：wjzy_yyds</p>
              <p className="mt-2 text-white/40 text-sm">扫码添加，备注「Yyds.Auto」</p>
            </div>
          </FadeInSection>
        </div>
      </section>

      {/* ── Footer ── */}
      <footer className="border-t border-white/[0.06] py-12 px-6">
        <div className="max-w-7xl mx-auto flex flex-col md:flex-row items-center justify-between gap-6">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center font-bold text-xs">Y</div>
            <span className="font-semibold text-sm">Yyds.Auto</span>
          </div>
          <div className="flex items-center gap-6 text-sm text-white/30">
            {NAV_ITEMS.map((item) => (
              <button key={item.id} onClick={() => scrollTo(item.id)} className="hover:text-white/60 transition-colors">{item.label}</button>
            ))}
          </div>
          <p className="text-xs text-white/20">© 2024 Yyds.Auto. All rights reserved.</p>
        </div>
      </footer>
    </div>
  );
}
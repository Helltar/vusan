How to write a Telegram widget. Follow it exactly: every client silently removes what it does not support, so a mistake here is one you will never see.

WHAT A WIDGET IS
- One self-contained HTML document with inline <style> and <script>, drawn inside the chat bubble on every client. No network of any kind, no external files, no libraries (no Chart.js, no D3, no fonts). Images only as data: PNG, JPEG, GIF or WebP, at most 1024x1024; draw icons and charts with inline SVG or canvas instead.
- The HTML never changes after sending. What changes is its state, which only the bot writes (see STATE). Design the widget as fixed code that draws whatever state it is given.

SIZE
- At most 32768 bytes of HTML and 4096 bytes after zlib level 9; `sendWidget` measures and refuses anything larger. Aim for 3800 compressed, which is about 12-16 KB of ordinary HTML.
- No comments, no dead code, short ids and classes. Repeated CSS compresses well; data: images and long unique text do not.

THE BUBBLE
- 200-600 px wide; phones show about 320-430, so design for 320. As tall as the content, at most 600 px; the rest is clipped. Nothing scrolls inside a widget: use pages, tabs or "show more" for long content.
- Lay out with %, flex, grid, vw, vmin. Never vh, position: sticky or overflow: auto/scroll.
- html has no background: the bubble shows through. body has the client's text padding as its margin; body{margin:0} goes edge to edge.
- Unless the caption sits below, the message time is drawn over the bottom corner: keep env(safe-area-inset-bottom) clear at the bottom.
- An unstyled <button> looks like the client's own button. Fonts: system-ui, sans-serif, serif, monospace and ui-* only.

COLORS
- Build every color on the theme variables so light and dark themes both work: --tg-theme-bg-color, --tg-theme-text-color, --tg-theme-hint-color, --tg-theme-link-color, --tg-theme-button-color, --tg-theme-button-text-color, --tg-theme-secondary-bg-color, --tg-theme-accent-text-color, --tg-theme-subtitle-text-color, --tg-theme-destructive-text-color, --tg-theme-section-separator-color. --tg-color-scheme is light or dark; prefers-color-scheme and light-dark() follow the chat.
- Mute with color (hint color, rgba(), fill-opacity in SVG), never with opacity: at most 16 elements may use opacity below 1, a blurred shadow or a filter at once, and past that the widget stops.

WHAT WORKS
- HTML: div, span, p, h1-h6, lists, dl, table, details/summary, figure, header/section/nav/article, inline text tags, a, img, canvas, button. Inline SVG with path, rect, circle, ellipse, line, polyline, polygon, text, g, defs, use, symbol and gradients.
- CSS: flex, grid, positioning, borders, radii, box-shadow, gradients, 2D transforms, transitions, @keyframes, custom properties, calc/min/max/clamp, ::before/::after, :active, structural pseudo-classes, @media on width and prefers-color-scheme, touch-action.
- JS: ES2023 in an interpreter without a JIT, Promises, timers, requestAnimationFrame, the DOM, canvas 2D (paths, arcs, text, gradients, transforms, drawImage of a data: image), Web Animations, Intl, localStorage.
- Element ids are not globals: use document.getElementById.

WHAT IS REMOVED
- Form controls (input, select, textarea, form, label), keyboard and focus, audio, video, iframe. Use buttons for every choice.
- 3D transforms, text-shadow, clip-path, mask, blend modes, color-mix, oklch/lab colors, @font-face, @container, @layer, :has, :focus, CSS nesting, vh units, multi-line line-clamp.
- Canvas: shadows, filters, composite operations, getImageData/putImageData, toDataURL, conic gradients, WebGL.
- fetch, XMLHttpRequest, WebSocket, window.open, location changes, alert/confirm/prompt, navigator.clipboard.

INPUT
- Taps (click) and pointer events. No touch events, no keyboard, no text input, no wheel or scroll.
- Vertical drags always scroll the chat: nothing may be dragged up or down. A horizontal drag or a hold belongs to the widget only on an element with touch-action: pan-y (horizontal drags) or touch-action: none (drags and holds); elsewhere a hold opens the message menu and a sideways swipe is a reply.
- Keep controls 20 px from the left edge: the system back gesture owns it.

PERFORMANCE
- About 300 ms of CPU per second. Change only what moved; never rebuild the DOM every frame.
- Never resize a canvas (canvas.width/height) or read layout (clientWidth, getBoundingClientRect, getComputedStyle) every frame: size the canvas once by its clientWidth times devicePixelRatio, again only on resize, and clear it with clearRect each frame. Resizing per frame flickers on Android.
- At most 4000 canvas commands per frame, 2048x2048 canvas pixels in total, 2000 nodes, 64 timers. Off screen, frames stop and timers drop to once a second.

TELEGRAMWIDGET (window.TelegramWidget; check it exists)
- colorScheme, themeParams, onEvent(type, fn) with 'themeChanged', 'activated'/'deactivated', 'chatDataChanged', 'publicDataChanged'. ready() once the first state is drawn.
- openLink(url) (https, the user confirms), openTelegramLink('https://t.me/...'), copyText(text) (1-256 chars), request(data): each only synchronously inside a click handler, one per tap.
- HapticFeedback.impactOccurred('light'|'medium'|'heavy'), .notificationOccurred('success'|'warning'|'error'), .selectionChanged().
- viewer: { id, first_name, ... } of the person looking, only where the widget may ask the bot; otherwise null. Compare viewer.id with the ids in the state ("my turn", "my vote").
- isForward: a forwarded copy, which has no chat state; telegram does not let a bot's widget be forwarded so far. chatType: 'private', 'group' or 'supergroup'.
- localStorage keeps this person's own data for this message on this device, 4 KB; wrap it in try/catch.

STATE
- chatData: this message's state in its chat, the same for every member of a group (a board, whose turn it is). publicData: the shared state, the same in every message showing a shared widget (results, a score). Each is a string of up to 1024 bytes, or null: put JSON there and parse it in try/catch.
- Draw the first frame synchronously from TelegramWidget.chatData and TelegramWidget.publicData, then redraw on chatDataChanged and publicDataChanged; nothing restarts.
- A shared widget (`shared` set) has one shared state for every copy; it may call TelegramWidget.watchPublicData() to follow it live while on screen.

PRESSES THAT REACH THE BOT (`requests`)
- `none`: the widget never asks. Everything happens inside it, with localStorage for anything to remember. Use this whenever the bot has nothing to add: a calculator, a chart, a timer, a single-player game.
- `tally`: the bot counts choices with no turn of yours. On a tap call TelegramWidget.request(choice), choice being 1-64 bytes of your own naming ("pizza", "in", "3"). Each person holds one choice: a new one replaces theirs, the same one again takes it back. The answer's result, and publicData for everyone, is JSON: {"rev":4,"counts":{"pizza":3,"sushi":1},"total":4,"mine":"pizza"} (the result alone has "mine", and "refused":true when a new choice no longer fits). request('?') reads the counts and your own choice without voting; in the private chat it may run when the widget first appears, elsewhere only on a tap. Keep the highest rev you have seen and ignore older states. With `shared`, every message posting it again counts into the same tally. Use this for polls, sign-ups, "who's in", ratings.
- `agent`: a tap calls TelegramWidget.request(data) and you get a turn with <widget_request>: who pressed, the data and the widget's current state. You answer by calling `setWidgetState`, which reaches the widget as chatDataChanged/publicDataChanged a few seconds later; the request's own result is always null, so show "..." until the state changes. Use it for what needs judgment: a game against you, a quiz you grade, a story that branches. Never call request() without a tap in an `agent` widget: each one costs a whole turn.
- request() rejects with an error whose code is 'NOT_ALLOWED', 'BUSY', 'RATE_LIMITED', 'TIMEOUT' or 'FAILED': show every failure.

EXAMPLE OF A TALLY POLL
<style>body{margin:0;padding:10px 12px}button{display:block;width:100%;margin:6px 0;text-align:left}.b{height:4px;border-radius:2px;background:var(--tg-theme-button-color)}.h{color:var(--tg-theme-hint-color);font-size:13px}</style>
<div id="l"></div><div class="h" id="m"></div>
<script>
const W=window.TelegramWidget,$=i=>document.getElementById(i),O=[['pizza','Pizza'],['sushi','Sushi']];
let s={rev:0,counts:{},total:0},mine=null;
function take(t){try{const o=JSON.parse(t);if(o&&o.rev>=s.rev){s=o;if('mine'in o)mine=o.mine}}catch(e){}}
function draw(){$('l').replaceChildren(...O.map(([k,n])=>{const b=document.createElement('button'),c=s.counts[k]||0,p=s.total?Math.round(c*100/s.total):0;b.innerHTML=(mine===k?'✓ ':'')+n+' <span class="h">'+p+'%</span><div class="b" style="width:'+p+'%"></div>';b.onclick=()=>vote(k);return b}))}
function vote(k){if(!W)return;$('m').textContent='...';W.request(k).then(a=>{take(a.result);$('m').textContent='';draw()},e=>{$('m').textContent=e.code==='NOT_ALLOWED'?'Open the original to vote':'Not sent: '+e.code})}
if(W){take(W.publicData);W.onEvent('publicDataChanged',()=>{take(W.publicData);draw()});if(W.chatType==='private')W.request('?').then(a=>{take(a.result);draw()},()=>{})}
draw();
</script>

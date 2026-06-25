// shell.jsx — phone-frame shell, top bar, bottom tab bar, tx detail sheet

const PHONE_W = 320;
const PHONE_H = 660;

// ─────────────────────────────────────────────────────────────
// PhoneShell — wraps each artboard's screen with status bar, theme class,
// and the tap-to-expand transaction sheet overlay.
// Optionally accepts onRefresh: a callback fired when the user pulls the
// scroller down past threshold (the new "swipe to sync" gesture).
// ─────────────────────────────────────────────────────────────
function PhoneShell({ theme = 'dark', children, hideTabs, onRefresh }) {
  const [openTx, setOpenTx] = React.useState(null);
  const [pull, setPull] = React.useState(0);
  const [phase, setPhase] = React.useState('idle'); // idle | pulling | refreshing | done
  const scrollRef = React.useRef(null);
  const dragRef = React.useRef({ active: false, startY: 0, captured: false });

  // Threshold past which the release commits a refresh.
  const THRESHOLD = 56;

  const onPointerDown = (e) => {
    if (!onRefresh || phase === 'refreshing') return;
    const sc = scrollRef.current;
    if (!sc || sc.scrollTop > 0) return;
    // Don't fight the tx sheet or other overlays.
    if (e.target.closest('[data-mfv-overlay]')) return;
    dragRef.current = { active: true, startY: e.clientY, captured: false, id: e.pointerId };
  };

  const onPointerMove = (e) => {
    const d = dragRef.current;
    if (!d.active) return;
    const dy = e.clientY - d.startY;
    if (dy <= 0) { setPull(0); setPhase('idle'); return; }
    // Capture once we've established the gesture is a downward pull, so we
    // don't intercept normal up-scrolling.
    if (!d.captured && dy > 4) {
      d.captured = true;
      try { scrollRef.current.setPointerCapture(d.id); } catch {}
      setPhase('pulling');
    }
    // Dampened pull — sqrt curve so it feels resistive.
    const pulled = Math.min(110, Math.sqrt(dy) * 9);
    setPull(pulled);
  };

  const finishDrag = () => {
    const d = dragRef.current;
    if (!d.active) return;
    try { scrollRef.current.releasePointerCapture(d.id); } catch {}
    const committed = pull >= THRESHOLD;
    dragRef.current = { active: false, startY: 0, captured: false };
    if (committed) {
      setPhase('refreshing');
      setPull(48);
      onRefresh?.();
      setTimeout(() => {
        setPhase('done');
        setPull(0);
        setTimeout(() => setPhase('idle'), 1800);
      }, 1100);
    } else {
      setPull(0);
      setPhase('idle');
    }
  };

  const ctx = React.useMemo(() => ({ openTx, setOpenTx }), [openTx]);

  // Indicator rotation: 0 at rest, 180° at threshold.
  const spinDeg = phase === 'refreshing' ? null : (pull / THRESHOLD) * 180;

  return (
    <PhoneCtx.Provider value={ctx}>
      <div className={`mfv ${theme}`} style={{
        width: '100%', height: '100%',
        position: 'relative', overflow: 'hidden',
        background: 'var(--bg)',
        display: 'flex', flexDirection: 'column',
      }}>
        <PhoneStatusBar theme={theme} />

        {/* Pull indicator — sits above the scroll, revealed as content slides down */}
        {onRefresh && (phase !== 'idle') && (
          <div style={{
            position: 'absolute', top: 38, left: 0, right: 0, zIndex: 4,
            display: 'flex', justifyContent: 'center', pointerEvents: 'none',
            height: Math.max(0, pull),
            alignItems: 'center',
          }}>
            <div style={{
              width: 34, height: 34, borderRadius: 17,
              background: 'var(--surface)', border: '1px solid var(--border)',
              boxShadow: 'var(--shadow)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: pull >= THRESHOLD ? 'var(--c-purple)' : 'var(--text-2)',
              transition: 'color .15s, transform .12s',
              transform: spinDeg != null ? `rotate(${spinDeg}deg)` : undefined,
              animation: phase === 'refreshing' ? 'mfv-spin 0.9s linear infinite' : 'none',
            }}>
              <MFVIcon name="sync" size={15} stroke={2.2} />
            </div>
          </div>
        )}

        <div
          ref={scrollRef}
          className="scroll"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={finishDrag}
          onPointerCancel={finishDrag}
          style={{
            flex: 1, overflowY: 'auto', overflowX: 'hidden',
            paddingBottom: hideTabs ? 0 : 86,
            transform: pull ? `translateY(${pull}px)` : 'none',
            transition: dragRef.current.active ? 'none' : 'transform .28s cubic-bezier(.2,.7,.3,1)',
            touchAction: onRefresh ? 'pan-y' : 'auto',
          }}>
          {children}
        </div>

        {/* "Synced just now" toast — brief feedback after refresh */}
        {phase === 'done' && (
          <div style={{
            position: 'absolute', top: 46, left: '50%', transform: 'translateX(-50%)',
            zIndex: 6, pointerEvents: 'none',
            padding: '6px 12px', borderRadius: 999,
            background: 'var(--surface)', border: '1px solid var(--border)',
            boxShadow: 'var(--shadow)',
            display: 'inline-flex', alignItems: 'center', gap: 6,
            fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 600,
            color: 'var(--c-positive)',
            animation: 'mfv-toast .5s cubic-bezier(.2,.7,.3,1) both',
          }}>
            <MFVIcon name="check" size={12} stroke={2.6} />
            <span>Synced just now</span>
          </div>
        )}

        {!hideTabs && <PhoneTabBar />}
        {openTx && <TxSheet tx={openTx} onClose={() => setOpenTx(null)} />}
      </div>
    </PhoneCtx.Provider>
  );
}

const PhoneCtx = React.createContext({ openTx: null, setOpenTx: () => {} });

// Compact iOS-style status bar (we draw our own, the iOS-frame component
// is bigger than we want inside a 320-wide artboard).
function PhoneStatusBar({ theme }) {
  const c = theme === 'dark' ? '#fff' : '#0B0B0F';
  return (
    <div style={{
      height: 38, padding: '14px 22px 8px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      fontFamily: 'var(--font-sans)', fontSize: 13, fontWeight: 600, color: c,
      flexShrink: 0,
    }}>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>9:41</span>
      <div style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
        <svg width="15" height="9" viewBox="0 0 15 9">
          <rect x="0" y="6" width="2.4" height="3" rx="0.5" fill={c} />
          <rect x="3.6" y="4" width="2.4" height="5" rx="0.5" fill={c} />
          <rect x="7.2" y="2" width="2.4" height="7" rx="0.5" fill={c} />
          <rect x="10.8" y="0" width="2.4" height="9" rx="0.5" fill={c} />
        </svg>
        <svg width="13" height="9" viewBox="0 0 13 9">
          <path d="M6.5 2.4C8.3 2.4 9.9 3.1 11 4.2L11.8 3.3C10.4 2 8.6 1.2 6.5 1.2C4.4 1.2 2.6 2 1.2 3.3L2 4.2C3.1 3.1 4.7 2.4 6.5 2.4Z" fill={c}/>
          <path d="M6.5 5.1C7.6 5.1 8.5 5.5 9.2 6.2L10 5.4C9 4.5 7.8 3.9 6.5 3.9C5.2 3.9 4 4.5 3 5.4L3.8 6.2C4.5 5.5 5.4 5.1 6.5 5.1Z" fill={c}/>
          <circle cx="6.5" cy="8" r="1" fill={c}/>
        </svg>
        <svg width="22" height="10" viewBox="0 0 22 10">
          <rect x="0.4" y="0.4" width="18.8" height="9.2" rx="2.8" stroke={c} strokeOpacity="0.35" fill="none"/>
          <rect x="1.6" y="1.6" width="16" height="6.8" rx="1.5" fill={c}/>
          <path d="M20.5 3.5V6.5C21.1 6.3 21.6 5.6 21.6 5C21.6 4.4 21.1 3.7 20.5 3.5Z" fill={c} fillOpacity="0.4"/>
        </svg>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Bottom tab bar with floating add FAB
// ─────────────────────────────────────────────────────────────
function PhoneTabBar({ active = 'home' }) {
  const items = [
    { id: 'home',  icon: 'home',  label: 'Home' },
    { id: 'goals', icon: 'target', label: 'Goals' },
    { id: 'add',   icon: 'plus',   label: '',     fab: true },
    { id: 'stats', icon: 'chart',  label: 'Stats' },
    { id: 'me',    icon: 'user',   label: 'Me' },
  ];
  return (
    <div style={{
      position: 'absolute', bottom: 0, left: 0, right: 0,
      paddingBottom: 14, paddingTop: 10, zIndex: 5,
      background: 'linear-gradient(180deg, transparent 0%, var(--bg) 30%)',
      pointerEvents: 'none',
    }}>
      <div style={{
        margin: '0 14px', height: 60, borderRadius: 22,
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        boxShadow: 'var(--shadow)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-around',
        padding: '0 6px', pointerEvents: 'auto',
        position: 'relative',
      }}>
        {items.map((it) => it.fab ? (
          <button key={it.id} className="tap" style={{
            width: 48, height: 48, borderRadius: 16,
            background: 'linear-gradient(135deg, var(--c-purple) 0%, #6244FF 100%)',
            border: 'none',
            boxShadow: '0 6px 18px rgba(124, 92, 255, 0.45)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', color: '#fff',
            transform: 'translateY(-12px)',
          }}>
            <MFVIcon name="plus" size={22} stroke={2.5} color="#fff" />
          </button>
        ) : (
          <button key={it.id} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
            color: it.id === active ? 'var(--text)' : 'var(--text-3)',
            padding: '8px 6px',
            fontFamily: 'var(--font-sans)', fontSize: 10, fontWeight: 500,
          }}>
            <MFVIcon name={it.icon} size={20} stroke={2} />
            <span>{it.label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Tx detail sheet — bottom-sheet card with email source + regret toggle
// ─────────────────────────────────────────────────────────────
function TxSheet({ tx, onClose }) {
  const [regret, setRegret] = React.useState(!!tx.regret);
  const cat = MFV_CATEGORIES[tx.cat];
  const m = mfvMoney(tx.amount, { cents: true });
  const isIncome = tx.amount > 0;
  return (
    <div onClick={onClose} data-mfv-overlay="" style={{
      position: 'absolute', inset: 0, zIndex: 30,
      background: 'rgba(0,0,0,0.5)',
      animation: 'mfv-fade .2s ease both',
      display: 'flex', alignItems: 'flex-end',
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width: '100%',
        background: 'var(--surface)',
        borderTopLeftRadius: 26, borderTopRightRadius: 26,
        padding: '12px 18px 22px',
        animation: 'mfv-sheet-in .35s cubic-bezier(.2,.7,.3,1) both',
        border: '1px solid var(--border)', borderBottom: 'none',
      }}>
        {/* grab handle */}
        <div style={{ width: 36, height: 4, borderRadius: 4, background: 'var(--border-2)', margin: '0 auto 14px' }} />

        <div className="row" style={{ gap: 12 }}>
          <MFVCatGlyph catId={tx.cat} size={40} radius={12} />
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: -0.2 }}>{tx.merchant}</div>
            <div style={{ fontSize: 12, color: 'var(--text-2)' }}>{cat?.label}</div>
          </div>
          <button onClick={onClose} style={{
            width: 28, height: 28, borderRadius: 14, border: 'none', cursor: 'pointer',
            background: 'var(--chip-bg)', color: 'var(--text-2)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <MFVIcon name="x" size={14} stroke={2.5} />
          </button>
        </div>

        <div className="num-display" style={{
          fontSize: 38, fontWeight: 600, marginTop: 18, letterSpacing: -1.2,
          color: isIncome ? 'var(--c-positive)' : 'var(--text)',
        }}>
          {m.whole}<span style={{ fontSize: 22, color: 'var(--text-2)' }}>.{m.cents}</span>
        </div>

        <div className="row" style={{ gap: 6, marginTop: 4, fontSize: 12, color: 'var(--text-2)' }}>
          <span>{tx.date} · {tx.time}</span>
          <span>·</span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <MFVIcon name="mail" size={11} stroke={2} />
            Synced from email
          </span>
        </div>

        {/* meta rows */}
        <div style={{ marginTop: 18, display: 'flex', flexDirection: 'column', gap: 1, borderRadius: 14, overflow: 'hidden', background: 'var(--surface-2)' }}>
          <SheetRow label="From" value={tx.source === 'bank' ? 'Bank · notification' : 'noreply@' + slugMerchant(tx.merchant)} />
          <SheetRow label="Note" value={tx.note} />
          <SheetRow label="Account" value="Visa ····4082" />
        </div>

        {!isIncome && (
          <div style={{
            marginTop: 14, padding: '14px 16px', borderRadius: 16,
            background: regret ? 'rgba(255,107,107,0.10)' : 'var(--surface-2)',
            border: `1px solid ${regret ? 'rgba(255,107,107,0.3)' : 'var(--border)'}`,
            display: 'flex', alignItems: 'center', gap: 12, transition: 'all .2s',
          }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 600 }}>Worth it?</div>
              <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 2 }}>Mark to review later</div>
            </div>
            <button onClick={() => setRegret(false)} className="tap" style={{
              width: 36, height: 36, borderRadius: 12, border: 'none', cursor: 'pointer',
              background: !regret ? 'var(--c-positive)' : 'var(--chip-bg)',
              color: !regret ? '#0B0B0F' : 'var(--text-2)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <MFVIcon name="thumbsUp" size={16} stroke={2} />
            </button>
            <button onClick={() => setRegret(true)} className="tap" style={{
              width: 36, height: 36, borderRadius: 12, border: 'none', cursor: 'pointer',
              background: regret ? 'var(--c-negative)' : 'var(--chip-bg)',
              color: regret ? '#fff' : 'var(--text-2)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <MFVIcon name="thumbsDown" size={16} stroke={2} />
            </button>
          </div>
        )}

        <div className="row" style={{ gap: 8, marginTop: 14 }}>
          <button className="tap" style={{
            flex: 1, height: 46, borderRadius: 14, cursor: 'pointer',
            background: 'var(--chip-bg)', color: 'var(--text)',
            border: '1px solid var(--border)',
            fontFamily: 'var(--font-sans)', fontSize: 13, fontWeight: 500,
          }}>Recategorize</button>
          <button className="tap" style={{
            flex: 1, height: 46, borderRadius: 14, cursor: 'pointer',
            background: 'var(--text)', color: 'var(--bg)',
            border: 'none',
            fontFamily: 'var(--font-sans)', fontSize: 13, fontWeight: 600,
          }}>View receipt</button>
        </div>
      </div>
    </div>
  );
}

function SheetRow({ label, value }) {
  return (
    <div className="row" style={{ padding: '11px 14px', justifyContent: 'space-between', gap: 12 }}>
      <span style={{ fontSize: 12, color: 'var(--text-2)' }}>{label}</span>
      <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)', textAlign: 'right' }}>{value}</span>
    </div>
  );
}

function slugMerchant(name) {
  return name.toLowerCase().split(/[^a-z]+/).filter(Boolean)[0] + '.com';
}

// ─────────────────────────────────────────────────────────────
// Tx row — used everywhere
// ─────────────────────────────────────────────────────────────
function TxRow({ tx, compact = false }) {
  const { setOpenTx } = React.useContext(PhoneCtx);
  const cat = MFV_CATEGORIES[tx.cat];
  const m = mfvMoney(tx.amount, { cents: true });
  const isIncome = tx.amount > 0;
  return (
    <button onClick={() => setOpenTx(tx)} className="tap" style={{
      width: '100%', border: 'none', background: 'transparent',
      padding: compact ? '8px 0' : '10px 0', cursor: 'pointer',
      display: 'flex', alignItems: 'center', gap: 12,
      fontFamily: 'var(--font-sans)', textAlign: 'left',
    }}>
      <MFVCatGlyph catId={tx.cat} size={compact ? 34 : 38} radius={compact ? 10 : 11} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="row" style={{ gap: 6 }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tx.merchant}</span>
          {tx.source === 'email' && !compact && (
            <span title="Synced from email" style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              width: 14, height: 14, borderRadius: 4,
              background: 'var(--c-cyan-soft)', color: 'var(--c-cyan)', flexShrink: 0,
            }}>
              <MFVIcon name="mail" size={9} stroke={2.5} />
            </span>
          )}
        </div>
        <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 1 }}>
          {tx.date} · {tx.time} {tx.regret ? '· flagged' : ''}
        </div>
      </div>
      <div className="num-display" style={{
        fontSize: 15, fontWeight: 600,
        color: isIncome ? 'var(--c-positive)' : 'var(--text)',
        letterSpacing: -0.3, textAlign: 'right',
      }}>
        {m.whole}<span style={{ fontSize: 11, color: 'var(--text-2)' }}>.{m.cents}</span>
      </div>
    </button>
  );
}

Object.assign(window, { PhoneShell, PhoneCtx, PhoneTabBar, TxSheet, TxRow, PHONE_W, PHONE_H });

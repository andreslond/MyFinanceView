// home.jsx — three home dashboard variations

// ─────────────────────────────────────────────────────────────
// HOME A — Classic balance + swipeable budget cards + recent tx
// ─────────────────────────────────────────────────────────────
function HomeA() {
  const balance = mfvMoney(MFV_BALANCE, { cents: true });
  const [activeBudget, setActiveBudget] = React.useState(0);
  const trackRef = React.useRef(null);

  // Swipe: scroll-snap on the track, dot indicator follows.
  const onScroll = () => {
    const el = trackRef.current;
    if (!el) return;
    const idx = Math.round(el.scrollLeft / (el.children[0]?.offsetWidth + 10 || 1));
    setActiveBudget(idx);
  };

  return (
    <div style={{ padding: '8px 18px 12px' }}>
      {/* Greeting */}
      <div className="between" style={{ marginBottom: 22 }}>
        <div className="row" style={{ gap: 10 }}>
          <Avatar />
          <div>
            <div style={{ fontSize: 12, color: 'var(--text-2)' }}>Good morning,</div>
            <div style={{ fontSize: 15, fontWeight: 600, letterSpacing: -0.2 }}>{MFV_USER.name}</div>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <IconBtn name="search" />
          <IconBtn name="bell" badge />
        </div>
      </div>

      {/* Balance hero */}
      <div style={{ marginBottom: 6 }}>
        <div className="row" style={{ gap: 8, marginBottom: 4 }}>
          <span style={{ fontSize: 12, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>Total balance</span>
          <button style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-3)', padding: 0, display: 'flex' }}>
            <MFVIcon name="eye" size={14} stroke={2} />
          </button>
        </div>
        <div className="num-display rise" style={{ fontSize: 44, fontWeight: 600, lineHeight: 1, letterSpacing: -1.6 }}>
          {balance.whole}<span style={{ fontSize: 22, color: 'var(--text-2)' }}>.{balance.cents}</span>
        </div>
        <div className="row" style={{ gap: 8, marginTop: 8 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 3,
            padding: '3px 9px 3px 7px', borderRadius: 999,
            background: 'rgba(52,224,161,0.14)', color: 'var(--c-positive)',
            fontSize: 11, fontWeight: 600, fontFamily: 'var(--font-mono)',
          }}>
            <MFVIcon name="arrow-up" size={11} stroke={2.5} /> +{MFV_NET_PCT}%
          </span>
          <span style={{ fontSize: 11, color: 'var(--text-2)' }}>vs last month</span>
        </div>
      </div>

      {/* Quick actions — Add · Scan · Unclassified · Forecast */}
      <div className="row" style={{ gap: 8, marginTop: 18, marginBottom: 22 }}>
        {[
          { icon: 'plus',     label: 'Add' },
          { icon: 'scan',     label: 'Scan' },
          { icon: 'inbox',    label: 'Unclassified', badge: 3 },
          { icon: 'forecast', label: 'Forecast' },
        ].map((a) => (
          <button key={a.label} className="tap" style={{
            flex: 1, padding: '11px 4px', borderRadius: 14, cursor: 'pointer',
            background: 'var(--surface)', border: '1px solid var(--border)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
            color: 'var(--text)', fontFamily: 'var(--font-sans)', fontSize: 10, fontWeight: 500,
            position: 'relative',
          }}>
            <span style={{ position: 'relative', display: 'inline-flex' }}>
              <MFVIcon name={a.icon} size={18} stroke={2} />
              {a.badge != null && (
                <span style={{
                  position: 'absolute', top: -6, right: -10,
                  minWidth: 16, height: 16, padding: '0 4px', borderRadius: 999,
                  background: 'var(--c-purple)', color: '#fff',
                  fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700,
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  border: '2px solid var(--bg)',
                }}>{a.badge}</span>
              )}
            </span>
            {a.label}
          </button>
        ))}
      </div>

      {/* Budgets — swipeable cards */}
      <div className="between" style={{ marginBottom: 10 }}>
        <span style={{ fontSize: 14, fontWeight: 600 }}>Budgets · May</span>
        <button style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-2)', fontSize: 12, fontWeight: 500 }}>See all</button>
      </div>
      <div ref={trackRef} onScroll={onScroll} className="scroll" style={{
        display: 'flex', gap: 10, overflowX: 'auto', scrollSnapType: 'x mandatory',
        margin: '0 -18px 8px', padding: '4px 18px 16px',
      }}>
        {MFV_BUDGETS.map((b, i) => (
          <BudgetSwipeCard key={b.cat} b={b} primary={i === 0} />
        ))}
      </div>
      <div className="row" style={{ gap: 4, justifyContent: 'center', marginBottom: 22 }}>
        {MFV_BUDGETS.map((_, i) => (
          <div key={i} style={{
            width: i === activeBudget ? 16 : 4, height: 4, borderRadius: 2,
            background: i === activeBudget ? 'var(--text)' : 'var(--border-2)',
            transition: 'width .2s',
          }} />
        ))}
      </div>

      {/* Recent transactions */}
      <div className="between" style={{ marginBottom: 4 }}>
        <span style={{ fontSize: 14, fontWeight: 600 }}>Recent activity</span>
        <span style={{ fontSize: 11, color: 'var(--text-2)' }}>{MFV_TX.length} this week</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {MFV_TX.slice(0, 6).map((tx) => <TxRow key={tx.id} tx={tx} />)}
      </div>
    </div>
  );
}

function BudgetSwipeCard({ b, primary }) {
  const cat = MFV_CATEGORIES[b.cat];
  const pct = (b.spent / b.budget) * 100;
  const left = b.budget - b.spent;
  return (
    <div style={{
      flex: '0 0 200px', scrollSnapAlign: 'start',
      padding: 16, borderRadius: 20,
      background: primary
        ? `linear-gradient(140deg, ${cat.color} 0%, color-mix(in oklch, ${cat.color} 70%, #000) 100%)`
        : 'var(--surface)',
      border: primary ? 'none' : '1px solid var(--border)',
      color: primary ? '#0B0B0F' : 'var(--text)',
      boxShadow: primary ? `0 10px 24px color-mix(in oklch, ${cat.color} 35%, transparent)` : 'none',
      minHeight: 132,
      display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
      position: 'relative', overflow: 'hidden',
    }}>
      {primary && (
        <svg width="200" height="120" style={{ position: 'absolute', right: -40, top: -20, opacity: 0.15 }}>
          <circle cx="100" cy="60" r="55" fill="none" stroke="#0B0B0F" strokeWidth="0.5" />
          <circle cx="100" cy="60" r="42" fill="none" stroke="#0B0B0F" strokeWidth="0.5" />
          <circle cx="100" cy="60" r="28" fill="none" stroke="#0B0B0F" strokeWidth="0.5" />
        </svg>
      )}
      <div className="row" style={{ gap: 8, position: 'relative' }}>
        <div style={{
          width: 28, height: 28, borderRadius: 9,
          background: primary ? 'rgba(11,11,15,0.18)' : `color-mix(in oklch, ${cat.color} 22%, transparent)`,
          color: primary ? '#0B0B0F' : cat.color,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <MFVIcon name={cat.icon} size={14} stroke={2.2} />
        </div>
        <span style={{ fontSize: 12, fontWeight: 600 }}>{cat.label}</span>
      </div>
      <div style={{ position: 'relative' }}>
        <div className="num-display" style={{ fontSize: 26, fontWeight: 600, letterSpacing: -0.8, lineHeight: 1 }}>
          ${b.spent.toLocaleString()}
        </div>
        <div style={{ fontSize: 11, opacity: 0.7, marginTop: 4 }}>
          of ${b.budget.toLocaleString()} · ${left} left
        </div>
        <div style={{
          marginTop: 10, height: 5, borderRadius: 3,
          background: primary ? 'rgba(11,11,15,0.18)' : 'var(--track)',
          overflow: 'hidden',
        }}>
          <div style={{
            width: `${Math.min(100, pct)}%`, height: '100%',
            background: primary ? '#0B0B0F' : cat.color,
            borderRadius: 3,
          }} />
        </div>
      </div>
    </div>
  );
}

function Avatar() {
  return (
    <div style={{
      width: 36, height: 36, borderRadius: 12,
      background: 'linear-gradient(135deg, var(--c-purple), var(--c-cyan))',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: '#fff', fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 600,
    }}>{MFV_USER.initial}</div>
  );
}

function IconBtn({ name, badge }) {
  return (
    <button className="tap" style={{
      width: 36, height: 36, borderRadius: 12, cursor: 'pointer',
      background: 'var(--surface)', border: '1px solid var(--border)',
      color: 'var(--text)', position: 'relative',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <MFVIcon name={name} size={16} stroke={2} />
      {badge && <span style={{
        position: 'absolute', top: 7, right: 7,
        width: 6, height: 6, borderRadius: 3, background: 'var(--c-negative)',
      }} />}
    </button>
  );
}

// ─────────────────────────────────────────────────────────────
// HOME B — Reflection-first · donut hero + insights + "worth it?"
// ─────────────────────────────────────────────────────────────
function HomeB() {
  // Build donut from budgets — spent per category, with a residue "left"
  // segment so the donut always closes the loop.
  const totalSpent = MFV_BUDGETS.reduce((s, b) => s + b.spent, 0);
  const segments = MFV_BUDGETS.map((b) => ({
    value: b.spent,
    color: MFV_CATEGORIES[b.cat].color,
    label: MFV_CATEGORIES[b.cat].label,
  }));

  return (
    <div style={{ padding: '8px 18px 12px' }}>
      {/* Header */}
      <div className="between" style={{ marginBottom: 18 }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--text-2)' }}>Tue, May 27</div>
          <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.6, marginTop: 2 }}>Hi {MFV_USER.name}.</div>
        </div>
        <Avatar />
      </div>

      {/* Donut hero */}
      <div className="card" style={{ padding: 18, marginBottom: 14 }}>
        <div style={{ fontSize: 11, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>Where did your money go?</div>
        <div className="row" style={{ gap: 16, marginTop: 12, alignItems: 'center' }}>
          <MFVDonut segments={segments} size={120} thickness={20}
            centerLabel="Spent" centerValue={`$${(totalSpent/1000).toFixed(1)}k`} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
            {MFV_BUDGETS.slice(0, 4).map((b) => {
              const cat = MFV_CATEGORIES[b.cat];
              const pct = Math.round((b.spent / totalSpent) * 100);
              return (
                <div key={b.cat} className="row" style={{ gap: 8 }}>
                  <div style={{ width: 8, height: 8, borderRadius: 2, background: cat.color, flexShrink: 0 }} />
                  <span style={{ fontSize: 11, color: 'var(--text-2)', flex: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cat.label}</span>
                  <span className="num" style={{ fontSize: 11, fontWeight: 600 }}>{pct}%</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* This-week insight */}
      <div className="card" style={{
        padding: 16, marginBottom: 14,
        background: 'linear-gradient(135deg, var(--c-purple-soft), transparent 70%), var(--surface)',
        border: '1px solid var(--c-purple-soft)',
      }}>
        <div className="row" style={{ gap: 8, marginBottom: 8 }}>
          <div style={{
            width: 24, height: 24, borderRadius: 8,
            background: 'var(--c-purple)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <MFVIcon name="sparkle" size={13} stroke={2.4} color="#fff" />
          </div>
          <span style={{ fontSize: 11, color: 'var(--c-purple)', fontWeight: 700, letterSpacing: 0.6, textTransform: 'uppercase' }}>This week</span>
        </div>
        <div style={{ fontSize: 14, fontWeight: 500, lineHeight: 1.45, color: 'var(--text)', textWrap: 'pretty' }}>
          You spent <b className="num">$132</b> more on <b>Shopping</b> than your weekly average. The Amazon keyboard is the outlier — flag it?
        </div>
        <div className="row" style={{ gap: 8, marginTop: 12 }}>
          <button className="tap" style={{
            padding: '7px 14px', borderRadius: 999, border: '1px solid var(--border-2)',
            background: 'transparent', color: 'var(--text)', cursor: 'pointer',
            fontFamily: 'var(--font-sans)', fontSize: 12, fontWeight: 500,
          }}>Review</button>
          <button className="tap" style={{
            padding: '7px 14px', borderRadius: 999, border: 'none',
            background: 'var(--text)', color: 'var(--bg)', cursor: 'pointer',
            fontFamily: 'var(--font-sans)', fontSize: 12, fontWeight: 600,
          }}>Flag purchase</button>
        </div>
      </div>

      {/* Stat trio */}
      <div className="row" style={{ gap: 8, marginBottom: 14 }}>
        <MiniStat label="Income" value={`+$${(MFV_INCOME_MTD/1000).toFixed(1)}k`} delta="+8%" positive />
        <MiniStat label="Spent" value={`$${(MFV_SPENT_MTD/1000).toFixed(1)}k`} delta="−12%" positive />
        <MiniStat label="Saved" value={`$${(MFV_SAVED_MTD/1000).toFixed(1)}k`} delta="+24%" positive accent />
      </div>

      {/* Worth it? */}
      <div className="between" style={{ marginBottom: 8 }}>
        <span style={{ fontSize: 14, fontWeight: 600 }}>Worth a second look</span>
        <span style={{ fontSize: 11, color: 'var(--text-2)' }}>3 this week</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {MFV_TX.filter((t) => t.amount < -60 && t.cat !== 'bills').slice(0, 3).map((tx) => (
          <ReflectionRow key={tx.id} tx={tx} />
        ))}
      </div>
    </div>
  );
}

function MiniStat({ label, value, delta, positive, accent }) {
  return (
    <div className="card" style={{
      flex: 1, padding: '12px 12px',
      background: accent ? 'linear-gradient(135deg, var(--c-cyan-soft), transparent), var(--surface)' : 'var(--surface)',
      borderColor: accent ? 'var(--c-cyan-soft)' : 'var(--border)',
    }}>
      <div style={{ fontSize: 10, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>{label}</div>
      <div className="num-display" style={{ fontSize: 18, fontWeight: 600, marginTop: 4, letterSpacing: -0.6 }}>{value}</div>
      <div className="num" style={{ fontSize: 10, color: positive ? 'var(--c-positive)' : 'var(--c-negative)', marginTop: 2, fontWeight: 600 }}>{delta}</div>
    </div>
  );
}

function ReflectionRow({ tx }) {
  const { setOpenTx } = React.useContext(PhoneCtx);
  const [vote, setVote] = React.useState(tx.regret ? 'down' : null);
  return (
    <div className="card" style={{ padding: '12px 14px' }}>
      <div className="row" style={{ gap: 10 }}>
        <MFVCatGlyph catId={tx.cat} size={34} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="row" style={{ gap: 4 }}>
            <span style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tx.merchant}</span>
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-2)' }}>{tx.note} · {tx.date}</div>
        </div>
        <div className="num-display" style={{ fontSize: 14, fontWeight: 600, letterSpacing: -0.3 }}>
          −${Math.abs(tx.amount).toFixed(0)}
        </div>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 10 }}>
        <button onClick={() => setVote('up')} className="tap" style={{
          flex: 1, padding: '7px', borderRadius: 10, cursor: 'pointer',
          background: vote === 'up' ? 'rgba(52,224,161,0.16)' : 'var(--chip-bg)',
          color: vote === 'up' ? 'var(--c-positive)' : 'var(--text-2)',
          border: '1px solid', borderColor: vote === 'up' ? 'rgba(52,224,161,0.3)' : 'transparent',
          fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 500,
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
        }}>
          <MFVIcon name="thumbsUp" size={12} stroke={2.2} /> Worth it
        </button>
        <button onClick={() => setVote('down')} className="tap" style={{
          flex: 1, padding: '7px', borderRadius: 10, cursor: 'pointer',
          background: vote === 'down' ? 'rgba(255,107,107,0.14)' : 'var(--chip-bg)',
          color: vote === 'down' ? 'var(--c-negative)' : 'var(--text-2)',
          border: '1px solid', borderColor: vote === 'down' ? 'rgba(255,107,107,0.3)' : 'transparent',
          fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 500,
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
        }}>
          <MFVIcon name="thumbsDown" size={12} stroke={2.2} /> Regret
        </button>
        <button onClick={() => setOpenTx(tx)} className="tap" style={{
          padding: '7px 10px', borderRadius: 10, cursor: 'pointer',
          background: 'var(--chip-bg)', color: 'var(--text-2)',
          border: 'none', fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 500,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <MFVIcon name="chevron" size={12} stroke={2.2} />
        </button>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// HOME C — Power user · dense stats grid, sparkline, compact bars
// ─────────────────────────────────────────────────────────────
function HomeC() {
  const [period, setPeriod] = React.useState('M');
  const periods = ['D', 'W', 'M', 'Y'];
  return (
    <div style={{ padding: '8px 16px 12px' }}>
      {/* Header */}
      <div className="between" style={{ marginBottom: 12 }}>
        <div className="row" style={{ gap: 8 }}>
          <Avatar />
          <div>
            <div style={{ fontSize: 11, color: 'var(--text-2)' }}>{MFV_USER.name} · Visa ····4082</div>
            <div className="row" style={{ gap: 6 }}>
              <MFVIcon name="sync" size={11} stroke={2.4} color="var(--c-positive)" />
              <span style={{ fontSize: 11, color: 'var(--c-positive)', fontWeight: 600 }}>Live · synced 2m ago</span>
            </div>
          </div>
        </div>
        <IconBtn name="bell" badge />
      </div>

      {/* Balance + period toggle */}
      <div className="card" style={{ padding: 16, marginBottom: 12, position: 'relative', overflow: 'hidden' }}>
        <div className="between">
          <div>
            <div style={{ fontSize: 10, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>Net balance</div>
            <div className="num-display" style={{ fontSize: 32, fontWeight: 600, letterSpacing: -1.2, marginTop: 2 }}>
              ${MFV_BALANCE.toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </div>
          </div>
          <div style={{ display: 'flex', background: 'var(--surface-2)', borderRadius: 10, padding: 2 }}>
            {periods.map((p) => (
              <button key={p} onClick={() => setPeriod(p)} style={{
                width: 22, height: 22, borderRadius: 7, border: 'none',
                background: period === p ? 'var(--text)' : 'transparent',
                color: period === p ? 'var(--bg)' : 'var(--text-2)',
                fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 600, cursor: 'pointer',
              }}>{p}</button>
            ))}
          </div>
        </div>
        {/* Sparkline */}
        <div style={{ marginTop: 14 }}>
          <MFVSparkline values={MFV_SPARKLINE} width={285} height={50} color="var(--c-purple)" />
          <div className="between" style={{ marginTop: 6, fontSize: 10, color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>
            <span>May 1</span>
            <span>May 14</span>
            <span>May 27</span>
          </div>
        </div>
      </div>

      {/* Stat grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 12 }}>
        <PowerStat label="Income" value={MFV_INCOME_MTD} delta="+8%" color="var(--c-positive)" />
        <PowerStat label="Spent" value={MFV_SPENT_MTD} delta="−12%" color="var(--c-purple)" />
        <PowerStat label="Saved" value={MFV_SAVED_MTD} delta="+24%" color="var(--c-cyan)" />
        <PowerStat label="Net" value={MFV_INCOME_MTD - MFV_SPENT_MTD} delta="+2.4%" color="var(--text)" />
      </div>

      {/* Budget bars */}
      <div className="between" style={{ marginBottom: 8 }}>
        <span style={{ fontSize: 13, fontWeight: 600 }}>Budgets</span>
        <span style={{ fontSize: 10, color: 'var(--text-2)' }}>
          ${MFV_BUDGETS.reduce((s,b)=>s+b.spent,0).toLocaleString()} / ${MFV_BUDGETS.reduce((s,b)=>s+b.budget,0).toLocaleString()}
        </span>
      </div>
      <div className="card" style={{ padding: 12, marginBottom: 12 }}>
        {MFV_BUDGETS.map((b, i) => {
          const cat = MFV_CATEGORIES[b.cat];
          const pct = (b.spent / b.budget) * 100;
          return (
            <div key={b.cat} className="row" style={{ gap: 10, padding: '7px 0', borderTop: i ? '1px solid var(--border)' : 'none' }}>
              <div style={{ width: 6, height: 6, borderRadius: 2, background: cat.color, flexShrink: 0 }} />
              <span style={{ fontSize: 12, fontWeight: 500, flex: '0 0 78px' }}>{cat.label}</span>
              <MFVBar pct={pct} color={cat.color} height={4} />
              <span className="num" style={{ fontSize: 11, color: 'var(--text-2)', flexShrink: 0, width: 38, textAlign: 'right' }}>
                {Math.round(pct)}%
              </span>
              <span className="num" style={{ fontSize: 10, color: 'var(--text-3)', flexShrink: 0, width: 24, textAlign: 'right',
                fontWeight: 600, color: b.trend > 0 ? 'var(--c-negative)' : 'var(--c-positive)' }}>
                {b.trend > 0 ? '+' : ''}{b.trend}
              </span>
            </div>
          );
        })}
      </div>

      {/* Compact tx list */}
      <div className="between" style={{ marginBottom: 4 }}>
        <span style={{ fontSize: 13, fontWeight: 600 }}>Last 24h</span>
        <span style={{ fontSize: 10, color: 'var(--text-2)' }}>4 events</span>
      </div>
      <div>
        {MFV_TX.slice(0, 4).map((tx) => <TxRow key={tx.id} tx={tx} compact />)}
      </div>
    </div>
  );
}

function PowerStat({ label, value, delta, color }) {
  const isNeg = String(delta).startsWith('−');
  return (
    <div className="card" style={{ padding: '10px 12px', position: 'relative', overflow: 'hidden' }}>
      <div className="between">
        <div style={{ fontSize: 10, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 }}>{label}</div>
        <div style={{ width: 6, height: 6, borderRadius: 3, background: color }} />
      </div>
      <div className="num-display" style={{ fontSize: 17, fontWeight: 600, marginTop: 4, letterSpacing: -0.5 }}>
        ${value >= 1000 ? `${(value/1000).toFixed(1)}k` : value.toFixed(0)}
      </div>
      <div className="num" style={{ fontSize: 10, color: isNeg ? 'var(--c-positive)' : (delta.startsWith('+') ? 'var(--c-positive)' : 'var(--c-negative)'),
        marginTop: 1, fontWeight: 600 }}>{delta}</div>
    </div>
  );
}

Object.assign(window, { HomeA, HomeB, HomeC, Avatar, IconBtn });

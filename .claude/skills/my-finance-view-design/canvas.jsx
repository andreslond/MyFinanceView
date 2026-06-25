// canvas.jsx — design canvas composition

function App() {
  // Global theme tweak — affects every artboard at once.
  const defaults = /*EDITMODE-BEGIN*/{
    "theme": "dark",
    "showAnnotations": true
  }/*EDITMODE-END*/;
  const [t, setTweak] = useTweaks(defaults);
  const theme = t.theme;

  return (
    <>
      <DesignCanvas>
        <DCSection id="intro" title="My Finance View" subtitle="Real-time expense tracking, synced from email · explore three directions">
          <DCArtboard id="brief" label="The brief" width={420} height={PHONE_H + 30}>
            <BriefCard theme={theme} />
          </DCArtboard>
        </DCSection>

        <DCSection id="home" title="01 · Home dashboard" subtitle="Three answers to ‘where does my money go?’ — tap a transaction, swipe the budget cards, pull down to sync">
          <DCArtboard id="home-a" label="A · Classic balance" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} onRefresh={() => {}}><HomeA /></PhoneShell>
          </DCArtboard>
          <DCArtboard id="home-b" label="B · Reflection-first" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} onRefresh={() => {}}><HomeB /></PhoneShell>
          </DCArtboard>
          <DCArtboard id="home-c" label="C · Power-user" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} onRefresh={() => {}}><HomeC /></PhoneShell>
          </DCArtboard>
        </DCSection>

        <DCSection id="goals" title="02 · Goals" subtitle="Savings, debt payoff, multi-goal pacing">
          <DCArtboard id="goals-main" label="Goals overview" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme}><GoalsScreen /></PhoneShell>
          </DCArtboard>
        </DCSection>

        <DCSection id="flow" title="03 · Email-sync flow" subtitle="What happens when a new transaction lands in your inbox">
          <DCArtboard id="step1" label="① Detected" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} hideTabs><AddTxFlow startStep={1} /></PhoneShell>
          </DCArtboard>
          <DCArtboard id="step2" label="② Categorize" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} hideTabs><AddTxFlow startStep={2} /></PhoneShell>
          </DCArtboard>
          <DCArtboard id="step3" label="③ Saved" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} hideTabs><AddTxFlow startStep={3} /></PhoneShell>
          </DCArtboard>
          <DCArtboard id="step-i" label="Try it ↻" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} hideTabs><AddTxFlow startStep={1} interactive /></PhoneShell>
          </DCArtboard>
        </DCSection>

        <DCSection id="detail" title="04 · Transaction sheet" subtitle="Tap any transaction in the home screens to expand — meta, regret toggle, recategorize">
          <DCArtboard id="tx-demo" label="Tap a transaction" width={PHONE_W} height={PHONE_H}>
            <PhoneShell theme={theme} onRefresh={() => {}}><HomeA /></PhoneShell>
          </DCArtboard>
        </DCSection>

        {t.showAnnotations && (
          <>
            <DCPostIt top={-44} left={520} rotate={-3} width={210}>
              Toggle dark/light from the Tweaks panel ↘
            </DCPostIt>
          </>
        )}
      </DesignCanvas>

      <TweaksPanel title="Tweaks">
        <TweakSection label="Theme">
          <TweakRadio label="Mode" value={t.theme}
            options={[{ value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }]}
            onChange={(v) => setTweak('theme', v)} />
        </TweakSection>
        <TweakSection label="Canvas">
          <TweakToggle label="Show post-it notes" value={t.showAnnotations} onChange={(v) => setTweak('showAnnotations', v)} />
        </TweakSection>
      </TweaksPanel>
    </>
  );
}

// ─────────────────────────────────────────────────────────────
// Brief card — fills the left "introduction" artboard.
// ─────────────────────────────────────────────────────────────
function BriefCard({ theme }) {
  return (
    <div className={`mfv ${theme}`} style={{
      width: '100%', height: '100%', padding: 28,
      background: 'var(--bg)', color: 'var(--text)',
      display: 'flex', flexDirection: 'column',
      fontFamily: 'var(--font-sans)',
    }}>
      <div style={{
        display: 'inline-flex', alignItems: 'center', gap: 8, alignSelf: 'flex-start',
        padding: '5px 10px', borderRadius: 999,
        background: 'var(--c-purple-soft)', color: 'var(--c-purple)',
        fontSize: 11, fontWeight: 600, letterSpacing: 0.3,
      }}>
        <span style={{ width: 6, height: 6, borderRadius: 3, background: 'var(--c-purple)' }} />
        Hi-fi prototype · 3 directions
      </div>

      <h1 style={{
        fontFamily: 'var(--font-sans)', fontSize: 38, fontWeight: 700,
        letterSpacing: -1.4, lineHeight: 1.02, margin: '20px 0 0',
        textWrap: 'balance',
      }}>
        My Finance View.
      </h1>
      <p style={{
        fontSize: 14, color: 'var(--text-2)', lineHeight: 1.5,
        marginTop: 10, marginBottom: 24, textWrap: 'pretty',
      }}>
        A clear answer to four questions: <span style={{ color: 'var(--text)' }}>where my money goes</span>,
        whether I'm on pace, what I might regret, and what to do next — all
        sourced from receipts that land in my inbox.
      </p>

      <div className="card" style={{ padding: 16, marginBottom: 10 }}>
        <div style={{ fontSize: 11, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>System</div>
        <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          <Token label="Geist" detail="UI" />
          <Token label="Geist Mono" detail="numbers" />
        </div>
        <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          <Swatch c="var(--c-purple)" name="Purple" />
          <Swatch c="var(--c-cyan)" name="Cyan" />
          <Swatch c="var(--c-positive)" name="Positive" />
          <Swatch c="var(--c-negative)" name="Negative" />
        </div>
      </div>

      <div className="card" style={{ padding: 16, marginBottom: 10 }}>
        <div style={{ fontSize: 11, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>Interactions</div>
        <ul style={{ margin: '10px 0 0', padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[
            ['→', 'Swipe the budget cards (A · Classic)'],
            ['↓', 'Pull down to sync from email'],
            ['↘', 'Tap any transaction → bottom sheet'],
            ['◐', 'Theme toggle in Tweaks panel'],
            ['✓', 'Try it ↻ — categorize a new email-sourced tx'],
          ].map(([k, v]) => (
            <li key={v} className="row" style={{ gap: 10, fontSize: 13, color: 'var(--text-2)' }}>
              <span className="num" style={{ color: 'var(--c-purple)', fontWeight: 600, width: 16 }}>{k}</span>
              {v}
            </li>
          ))}
        </ul>
      </div>

      <div style={{ marginTop: 'auto', fontSize: 11, color: 'var(--text-3)', letterSpacing: 0.3 }}>
        Sample data · 320 × 660 frames
      </div>
    </div>
  );
}

function Token({ label, detail }) {
  return (
    <div style={{
      padding: '4px 9px', borderRadius: 8,
      background: 'var(--chip-bg)',
      fontSize: 11, color: 'var(--text)', fontWeight: 500,
      display: 'inline-flex', alignItems: 'baseline', gap: 5,
    }}>
      {label}
      <span style={{ color: 'var(--text-3)', fontSize: 10 }}>{detail}</span>
    </div>
  );
}

function Swatch({ c, name }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <div style={{ width: 14, height: 14, borderRadius: 4, background: c, border: '1px solid var(--border)' }} />
      <span style={{ fontSize: 11, color: 'var(--text-2)' }}>{name}</span>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);

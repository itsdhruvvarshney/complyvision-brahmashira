import { useEffect, useRef, useState } from 'react'
import handMain from '../assets/login-hand-1.webp'
import handFinger from '../assets/login-hand-2.webp'
import { supabase, supabaseConfigured } from '../lib/supabaseClient'
import './LoginPage.css'

const emailPattern = /^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/

async function otpRequest(path, payload) {
  let response
  try {
    response = await fetch(`/api/otp/${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch {
    throw new Error('OTP server is unreachable. Start it with `npm run dev` from the frontend folder.')
  }
  const result = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new Error(result.error || `OTP server returned an unexpected response (${response.status}). Check that the backend is running.`)
  }
  return result
}

export function LoginPage({ onAuthenticated }) {
  const [portal, setPortal] = useState('lm')
  const [registering, setRegistering] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const [loginMessage, setLoginMessage] = useState('')
  const [registrationMessage, setRegistrationMessage] = useState('')
  const [organization, setOrganization] = useState('')
  const [businessType, setBusinessType] = useState('')
  const [owner, setOwner] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [codeVisible, setCodeVisible] = useState(false)
  const [verified, setVerified] = useState(false)
  const [verificationToken, setVerificationToken] = useState('')
  const [verificationMessage, setVerificationMessage] = useState('')
  const [verificationState, setVerificationState] = useState('')
  const [otpPurpose, setOtpPurpose] = useState('registration')
  const [cooldown, setCooldown] = useState(0)
  const [sending, setSending] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [signingIn, setSigningIn] = useState(false)
  const [replayKey, setReplayKey] = useState(0)
  const passwordRef = useRef(null)

  useEffect(() => {
    // Login uses the same shared light palette as the dashboard, without a local theme control.
    document.documentElement.dataset.theme = 'light'
  }, [])

  useEffect(() => {
    if (cooldown <= 0) return undefined
    const timer = window.setTimeout(() => setCooldown((current) => current - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  function resetVerification() {
    setVerified(false)
    setVerificationToken('')
    setCodeVisible(false)
    setCode('')
    setCooldown(0)
    setVerificationMessage('')
    setVerificationState('')
  }

  async function sendCode(purpose = 'registration') {
    const normalizedEmail = email.trim().toLowerCase()
    if (!emailPattern.test(normalizedEmail)) {
      setVerificationState('bad')
      setVerificationMessage('Enter a valid business email first.')
      return
    }
    setSending(true)
    setOtpPurpose(purpose)
    setVerificationMessage('')
    try {
      if (purpose === 'login' && supabaseConfigured) {
        const { error } = await supabase.auth.signInWithOtp({ email: normalizedEmail, options: { shouldCreateUser: false } })
        if (error) throw error
      } else await otpRequest('send', { email: normalizedEmail })
      setCodeVisible(true)
      setCooldown(30)
      setVerificationState('')
      setVerificationMessage(`Code sent to ${normalizedEmail}. It expires in 10 minutes.`)
    } catch (error) {
      setVerificationState('bad')
      setVerificationMessage(error.message || 'Could not send the code. Try again.')
    } finally {
      setSending(false)
    }
  }

  async function confirmCode() {
    if (!/^\d{6}$/.test(code.trim())) {
      setVerificationState('bad')
      setVerificationMessage('Enter the 6-digit code.')
      return
    }
    setConfirming(true)
    try {
      if (otpPurpose === 'login' && supabaseConfigured) {
        const { data, error } = await supabase.auth.verifyOtp({ email: email.trim().toLowerCase(), token: code.trim(), type: 'email' })
        if (error) throw error
        const authRole = data.user?.app_metadata?.role
        if (!['organisation', 'organization', 'business_owner'].includes(authRole)) {
          await supabase.auth.signOut()
          throw new Error('This email is not enabled for the Pre-verification portal.')
        }
        setVerified(true)
        setVerificationToken('supabase-auth')
        setCodeVisible(false)
        setCooldown(0)
        setVerificationState('ok')
        setVerificationMessage('Email verified. Continue to your dashboard.')
        return
      }
      const result = await otpRequest('verify', { email: email.trim().toLowerCase(), code: code.trim() })
      if (!result.ok) {
        setVerificationState('bad')
        setVerificationMessage('That code is incorrect or expired. Check it or resend.')
        return
      }
      setVerified(true)
      setVerificationToken(result.verificationToken || '')
      setCodeVisible(false)
      setCooldown(0)
      setVerificationState('ok')
      setVerificationMessage('Email verified.')
    } catch (error) {
      setVerificationState('bad')
      setVerificationMessage(error.message || 'Could not verify the code. Try again.')
    } finally {
      setConfirming(false)
    }
  }

  async function submitLogin(event) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const userId = String(data.get('userId') || '').trim()
    const password = String(data.get('password') || '')
    if (!userId || (portal === 'lm' && !password)) { setLoginMessage('Enter your officer ID and password.'); return }
    if (portal === 'pre' && !emailPattern.test(userId)) {
      setLoginMessage('Enter a valid business email.')
      return
    }
    if (portal === 'pre' && (!verified || otpPurpose !== 'login' || !verificationToken)) {
      setLoginMessage('Verify your business email with the one-time code before continuing.')
      return
    }
    const nextRole = portal === 'lm'
      ? (userId.trim().toUpperCase().startsWith('S') ? 'supreme' : 'inspector')
      : 'organisation'
    if (portal === 'pre' && verificationToken === 'supabase-auth') {
      onAuthenticated?.(nextRole, userId.trim())
      window.location.hash = '/dashboard'
      return
    }
    if (!supabaseConfigured) {
      onAuthenticated?.(nextRole, userId.trim())
      window.location.hash = nextRole === 'supreme' ? '/dashboard/supreme' : nextRole === 'inspector' ? '/dashboard/inspector' : '/dashboard'
      return
    }
    setSigningIn(true)
    const email = portal === 'lm' ? `${userId.trim().toLowerCase()}@lm.complyvision.local` : userId.trim().toLowerCase()
    setLoginMessage('')
    try {
      const { data, error } = await supabase.auth.signInWithPassword({ email, password })
      if (error) throw error
      const authRole = data.user?.app_metadata?.role
      const allowedRoles = portal === 'lm'
        ? ['legal_metrology', 'supreme_authority', 'inspector']
        : ['organisation', 'organization', 'business_owner']
      if (!allowedRoles.includes(authRole)) {
        await supabase.auth.signOut()
        throw new Error('This account is not enabled for the selected portal.')
      }
      onAuthenticated?.(nextRole, userId.trim())
      window.location.hash = nextRole === 'supreme' ? '/dashboard/supreme' : nextRole === 'inspector' ? '/dashboard/inspector' : '/dashboard'
    } catch (error) {
      setLoginMessage(error.message || 'Sign-in failed. Check your credentials and try again.')
    } finally {
      setSigningIn(false)
    }
  }

  async function submitRegistration(event) {
    event.preventDefault()
    if (!organization.trim()) return setRegistrationMessage('Enter your organization name.')
    if (!businessType) return setRegistrationMessage('Select your business type.')
    if (!owner.trim()) return setRegistrationMessage('Enter the business owner’s name.')
    if (!verified || !verificationToken) return setRegistrationMessage('Verify your business email to continue.')
    setRegistrationMessage('')
    try {
      const response = await fetch('/api/registration', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ organization, businessType, owner, email: email.trim().toLowerCase(), verificationToken }),
      })
      const result = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(result.error || 'Registration could not be completed.')
      setRegistrationMessage('Your email is verified and this prototype accepted the registration. Account data is currently held only in the API process; durable Supabase registration is pending.')
    } catch (error) {
      setRegistrationMessage(error.message || 'Registration could not be completed.')
    }
  }

  const isOfficer = portal === 'lm'
  const heading = registering ? 'Register your business' : isOfficer ? 'Official sign in' : 'Pre-verification'
  const subtitle = registering
    ? 'Verify your business email to get started.'
    : isOfficer ? 'Legal Metrology department officers only.' : 'Check your product labels before inspection.'
  const note = !supabaseConfigured
    ? 'Preview mode: credentials are not verified. Dashboard actions use temporary sample data until Supabase is connected.'
    : registering
    ? 'We’ll email you a link to set your password once you are verified.'
    : isOfficer ? 'Government officers only. Every sign in is logged.' : 'For businesses preparing for a Legal Metrology inspection.'

  return (
    <main className="login-page">
      <div className="login-wrap">
        <section className="login-stage" aria-label="ComplyVision preview">
          <a className="login-brand" href="#/" aria-label="ComplyVision home"><span>✓</span>ComplyVision</a>
          <p className="login-tag">Check packaged goods against Legal Metrology, BIS and FSSAI rules, straight from a photo of the label.</p>
          <div className="login-replay">Tap the phone to replay</div>
          <div className="login-scene">
            <img className="login-hand login-hand-main" src={handMain} alt="" />
            <img className="login-hand login-hand-finger" src={handFinger} alt="" />
            <button className="login-phone" type="button" onClick={() => setReplayKey((key) => key + 1)} aria-label="Replay the label verification animation">
              <span className="login-screen" key={replayKey}>
                <span className="login-phone-status"><span>9:41</span><span>Scanning label</span></span>
                <span className="login-pack"><b>Golden Oats</b><i /><i /><i /><span className="login-scan" /></span>
                <span className="login-rows">
                  {['Net quantity · 500 g', 'MRP · ₹120 incl. taxes', 'FSSAI licence no.', 'ISI mark · BIS'].map((row, index) => <span className={`login-row${index === 3 ? ' login-row-bad' : ''}`} key={row}><span>{row}</span><b>✓</b></span>)}
                </span>
                <span className="login-verdict">Label verified</span>
              </span>
            </button>
            <div className="login-chip login-chip-one"><b>Legal Metrology</b><span>Packaged Commodities Rules, 2011</span></div>
            <div className="login-chip login-chip-two"><b>BIS</b><span>ISI mark and licence number</span></div>
            <div className="login-chip login-chip-three"><b>FSSAI</b><span>Labelling and Display Regulations, 2020</span></div>
          </div>
        </section>

        <section className="login-panel" aria-label="Account access">
          <div className="login-card">
            <h1>{heading}</h1>
            <p className="login-subtitle">{subtitle}</p>
            <div className="login-portals" role="radiogroup" aria-label="Select your portal">
              <button type="button" className="login-portal" role="radio" aria-checked={isOfficer} onClick={() => { setPortal('lm'); setRegistering(false); resetVerification(); setLoginMessage('') }}><i /><span><strong>Legal Metrology</strong><small>Officer sign in</small></span></button>
              <button type="button" className="login-portal" role="radio" aria-checked={!isOfficer} onClick={() => { setPortal('pre'); setRegistering(false); resetVerification(); setLoginMessage('') }}><i /><span><strong>Pre-verification</strong><small>For businesses</small></span></button>
            </div>

            {!registering ? <form onSubmit={submitLogin} noValidate>
              <label htmlFor="login-user">{isOfficer ? 'Officer ID' : 'Business email'}</label>
              <input id="login-user" name="userId" type={isOfficer ? 'text' : 'email'} value={isOfficer ? undefined : email} onChange={isOfficer ? undefined : (event) => { setEmail(event.target.value); if (verified || codeVisible) resetVerification() }} autoComplete={isOfficer ? 'username' : 'email'} placeholder={isOfficer ? 'e.g. LM-HR-20418' : 'name@company.com'} />
              {isOfficer && <small className="login-field-hint">Officer IDs starting with S open the Supreme Authority dashboard; other IDs open the Inspector dashboard.</small>}
              {isOfficer ? <><label htmlFor="login-password">Password</label>
                <div className="login-password-field"><input ref={passwordRef} id="login-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder="Enter your password" onKeyUp={(event) => setCapsLock(event.getModifierState?.('CapsLock') || false)} onBlur={() => setCapsLock(false)} /><button type="button" onClick={() => setShowPassword((show) => !show)} aria-label={`${showPassword ? 'Hide' : 'Show'} password`}>{showPassword ? 'Hide' : 'Show'}</button></div>
                {capsLock && <div className="login-caps">Caps Lock is on.</div>}
                <div className="login-row-between"><label className="login-trust"><input type="checkbox" />Trust this device</label><a href="#/login">Forgot password?</a></div>
              </> : <>
                <div className="login-code-row"><button className="login-verify-button" type="button" onClick={() => sendCode('login')} disabled={sending || cooldown > 0 || verified}>{sending ? 'Sending…' : cooldown ? `Resend in ${cooldown}s` : codeVisible ? 'Resend code' : 'Send verification code'}</button></div>
                {codeVisible && <div className="login-code-row login-confirm-row"><input value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); confirmCode() } }} inputMode="numeric" maxLength={6} placeholder="6-digit code" aria-label="Email verification code" autoComplete="one-time-code" /><button className="login-verify-button" type="button" onClick={confirmCode} disabled={confirming}>{confirming ? 'Checking…' : 'Confirm'}</button></div>}
                <p className={`login-verification-status ${verificationState}`} role="status">{verificationMessage}</p>
              </>}
              {!isOfficer && <div className="login-alt">New business? <button type="button" onClick={() => { setRegistering(true); setLoginMessage('') }}>Register for pre-verification</button></div>}
              <button className="login-submit" type="submit" disabled={signingIn || (!isOfficer && !verified)}>{signingIn ? 'Signing in…' : isOfficer ? 'Sign in to Legal Metrology' : 'Continue to Pre-verification'}</button>
              {import.meta.env.DEV && (isOfficer
                ? <div className="login-preview-options"><button className="login-preview-button" type="button" onClick={() => { onAuthenticated?.('inspector', 'INSPECTOR-DEMO', true); window.location.hash = '/dashboard/inspector' }}>Preview Inspector dashboard</button><button className="login-preview-button" type="button" onClick={() => { onAuthenticated?.('supreme', 'S-DEMO', true); window.location.hash = '/dashboard/supreme' }}>Preview Supreme Authority dashboard</button></div>
                : <button className="login-preview-button" type="button" onClick={() => { const previewEmail = email.trim() || 'preview@complyvision.local'; onAuthenticated?.('organisation', previewEmail, true); window.location.hash = '/dashboard' }}>Preview dashboard with sample data</button>)}
              <p className="login-message" role="status">{loginMessage}</p>
            </form> : <form onSubmit={submitRegistration} noValidate>
              <label htmlFor="login-organization">Organization name</label>
              <input id="login-organization" value={organization} onChange={(event) => setOrganization(event.target.value)} autoComplete="organization" placeholder="Registered business name" />
              <div className="login-two-fields"><div><label htmlFor="login-business-type">Business type</label><select id="login-business-type" value={businessType} onChange={(event) => setBusinessType(event.target.value)}><option value="">Select type</option><option>Proprietorship</option><option>Partnership</option><option>LLP</option><option>Pvt. Ltd</option></select></div><div><label htmlFor="login-owner">Business owner</label><input id="login-owner" value={owner} onChange={(event) => setOwner(event.target.value)} autoComplete="name" placeholder="Owner’s full name" /></div></div>
              <label htmlFor="login-email">Business email</label>
              <div className="login-code-row"><input id="login-email" type="email" value={email} onChange={(event) => { setEmail(event.target.value); if (verified || codeVisible) resetVerification() }} autoComplete="email" placeholder="name@company.com" /><button className="login-verify-button" type="button" onClick={sendCode} disabled={sending || cooldown > 0 || verified}>{verified ? 'Verified ✓' : sending ? 'Sending…' : cooldown ? `Resend in ${cooldown}s` : codeVisible ? 'Resend code' : 'Send code'}</button></div>
              {codeVisible && <div className="login-code-row login-confirm-row"><input value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); confirmCode() } }} inputMode="numeric" maxLength={6} placeholder="6-digit code" aria-label="Email verification code" autoComplete="one-time-code" /><button className="login-verify-button" type="button" onClick={confirmCode} disabled={confirming}>{confirming ? 'Checking…' : 'Confirm'}</button></div>}
              <p className={`login-verification-status ${verificationState}`} role="status">{verificationMessage}</p>
              <button className="login-submit" type="submit" disabled={!verified}>Register</button>
              <p className="login-message" role="status">{registrationMessage}</p>
              <div className="login-alt">Already registered? <button type="button" onClick={() => { setRegistering(false); setRegistrationMessage('') }}>Sign in</button></div>
            </form>}
            <p className="login-note">{note}</p>
            <div className="login-foot"><span>Secure email verification</span><a href="#/login">Report a problem</a></div>
          </div>
        </section>
      </div>
    </main>
  )
}

// ComplyVision email OTP API (Node.js 18+)
// Copy .env.example to .env and provide SMTP credentials before starting the server.
require('dotenv').config()
const crypto = require('node:crypto')
const express = require('express')
const nodemailer = require('nodemailer')
const cors = require('cors')
const { rateLimit } = require('express-rate-limit')

const app = express()
const port = Number(process.env.PORT) || 3000
const allowedOrigins = (process.env.ALLOWED_ORIGIN || 'http://localhost:5173')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean)

app.use(cors({
  origin(origin, callback) {
    if (!origin || allowedOrigins.includes(origin)) return callback(null, true)
    return callback(new Error('Origin is not allowed by CORS.'))
  },
}))
app.use(express.json({ limit: '10kb' }))
app.use('/api/otp', rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: true, legacyHeaders: false }))

const smtpPort = Number(process.env.SMTP_PORT) || 465
const mailer = nodemailer.createTransport({
  host: process.env.SMTP_HOST,
  port: smtpPort,
  secure: smtpPort === 465,
  auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
})
const otpSecret = process.env.OTP_SECRET
const otpStore = new Map()
const verifiedEmails = new Map()
const registrations = new Map()
const otpLifetimeMs = 10 * 60 * 1000
const resendDelayMs = 30 * 1000
const verificationLifetimeMs = 15 * 60 * 1000
const emailIsValid = (email) => /^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email)
const otpHash = (email, code) => crypto.createHmac('sha256', otpSecret).update(`${email}:${code}`).digest('hex')

app.get('/api/health', (_req, res) => res.json({ ok: true }))

app.post('/api/otp/send', async (req, res) => {
  if (!otpSecret) return res.status(503).json({ error: 'OTP_SECRET is missing. Configure backend/.env and restart the API.' })
  if (!process.env.SMTP_HOST || !process.env.SMTP_USER || !process.env.SMTP_PASS || !process.env.MAIL_FROM) {
    return res.status(503).json({ error: 'SMTP settings are missing. Configure SMTP_HOST, SMTP_USER, SMTP_PASS, and MAIL_FROM in backend/.env.' })
  }
  const email = String(req.body?.email || '').trim().toLowerCase()
  if (!emailIsValid(email)) return res.status(400).json({ error: 'Enter a valid email.' })

  const previous = otpStore.get(email)
  if (previous && Date.now() - previous.sentAt < resendDelayMs) {
    return res.status(429).json({ error: 'Wait 30 seconds before requesting another code.' })
  }

  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0')
  try {
    await mailer.sendMail({
      from: process.env.MAIL_FROM,
      to: email,
      subject: 'Your ComplyVision verification code',
      text: `Your verification code is ${code}. It expires in 10 minutes. If you did not request it, ignore this email.`,
    })
    otpStore.set(email, { hash: otpHash(email, code), expiresAt: Date.now() + otpLifetimeMs, attempts: 0, sentAt: Date.now() })
    return res.json({ ok: true })
  } catch (error) {
    console.error('OTP email delivery failed:', error.message)
    return res.status(502).json({ error: 'Could not send the email. Try again.' })
  }
})

app.post('/api/otp/verify', (req, res) => {
  if (!otpSecret) return res.status(503).json({ error: 'OTP_SECRET is missing. Configure backend/.env and restart the API.' })
  const email = String(req.body?.email || '').trim().toLowerCase()
  const code = String(req.body?.code || '')
  if (!emailIsValid(email) || !/^\d{6}$/.test(code)) return res.status(400).json({ error: 'Enter a valid email and 6-digit code.' })

  const record = otpStore.get(email)
  if (!record || Date.now() > record.expiresAt) {
    otpStore.delete(email)
    return res.status(400).json({ error: 'Code expired. Request a new one.' })
  }
  record.attempts += 1
  if (record.attempts > 5) {
    otpStore.delete(email)
    return res.status(429).json({ error: 'Too many attempts. Request a new code.' })
  }

  const expected = Buffer.from(record.hash)
  const supplied = Buffer.from(otpHash(email, code))
  if (!crypto.timingSafeEqual(expected, supplied)) return res.json({ ok: false })

  otpStore.delete(email)
  const verificationToken = crypto.randomBytes(32).toString('hex')
  verifiedEmails.set(verificationToken, { email, expiresAt: Date.now() + verificationLifetimeMs })
  return res.json({ ok: true, verificationToken })
})

app.post('/api/registration', rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false }), (req, res) => {
  const organization = String(req.body?.organization || '').trim()
  const businessType = String(req.body?.businessType || '').trim()
  const owner = String(req.body?.owner || '').trim()
  const email = String(req.body?.email || '').trim().toLowerCase()
  const verificationToken = String(req.body?.verificationToken || '')

  if (!organization || !businessType || !owner || !emailIsValid(email)) {
    return res.status(400).json({ error: 'Complete all registration fields with a valid email.' })
  }
  const verification = verifiedEmails.get(verificationToken)
  if (!verification || verification.email !== email || Date.now() > verification.expiresAt) {
    verifiedEmails.delete(verificationToken)
    return res.status(403).json({ error: 'Verify this email before registering.' })
  }
  if (registrations.has(email)) return res.status(409).json({ error: 'A registration already exists for this email.' })

  registrations.set(email, { organization, businessType, owner, email, createdAt: new Date().toISOString() })
  verifiedEmails.delete(verificationToken)
  return res.status(201).json({ ok: true })
})

app.listen(port, '127.0.0.1', () => console.log(`ComplyVision OTP API listening on http://127.0.0.1:${port}`))

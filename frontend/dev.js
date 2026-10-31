import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDir = dirname(fileURLToPath(import.meta.url))
const backendDir = resolve(frontendDir, '../backend')
const viteEntry = resolve(frontendDir, 'node_modules/vite/bin/vite.js')
const children = [
  spawn(process.execPath, [viteEntry], { cwd: frontendDir, stdio: 'inherit' }),
  spawn('npm', ['--prefix', backendDir, 'run', 'dev'], { cwd: frontendDir, stdio: 'inherit' }),
]

let shuttingDown = false
function stop(signal = 'SIGTERM') {
  if (shuttingDown) return
  shuttingDown = true
  for (const child of children) if (!child.killed) child.kill(signal)
}

const [webProcess, apiProcess] = children
webProcess.on('error', (error) => {
  console.error(`Could not start Vite: ${error.message}`)
  process.exitCode = 1
  stop()
})
webProcess.on('exit', (code) => {
  if (!shuttingDown) {
    process.exitCode = code || 0
    stop()
  }
})
apiProcess.on('error', (error) => {
  console.error(`Could not start the OTP API: ${error.message}`)
})
apiProcess.on('exit', (code) => {
  if (code && !shuttingDown) console.error('OTP API stopped; Vite is still running.')
})

process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))

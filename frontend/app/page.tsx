'use client'

import { useCallback, useState } from 'react'
import VantisLanding from '@/components/landing/vantis-landing'
import { StartupLoader } from '@/components/landing/startup-loader'

export default function Page() {
  const [loaded, setLoaded] = useState(false)
  const handleComplete = useCallback(() => setLoaded(true), [])

  return (
    <>
      {!loaded && <StartupLoader onComplete={handleComplete} />}
      <VantisLanding />
    </>
  )
}
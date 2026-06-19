import {
  Sun, CloudSun, Cloud, CloudFog, CloudDrizzle, CloudRain,
  CloudSnow, CloudLightning, CloudHail, Snowflake,
} from 'lucide-vue-next'
import type { Component } from 'vue'

/**
 * Map a backend WMO label (see WeatherService.decodeWmoCode) to a lucide icon.
 * Anything unknown returns null so the caller can skip rendering.
 */
export function weatherIcon(condition: string | null | undefined): Component | null {
  if (!condition) return null
  switch (condition) {
    case 'Clear sky': return Sun
    case 'Partly cloudy': return CloudSun
    case 'Fog': return CloudFog
    case 'Drizzle': return CloudDrizzle
    case 'Freezing drizzle': return CloudDrizzle
    case 'Rain': return CloudRain
    case 'Freezing rain': return CloudRain
    case 'Snow': return CloudSnow
    case 'Snow grains': return Snowflake
    case 'Rain showers': return CloudRain
    case 'Snow showers': return CloudSnow
    case 'Thunderstorm': return CloudLightning
    case 'Thunderstorm with hail': return CloudHail
    default: return Cloud
  }
}

/**
 * A subtle text-color hint per condition family — keeps weather chips visually
 * separable without relying on backgrounds. Returns a Tailwind text-* class.
 */
export function weatherColor(condition: string | null | undefined): string {
  if (!condition) return 'text-muted-fg'
  if (condition === 'Clear sky') return 'text-amber-500'
  if (condition.includes('Snow') || condition.includes('Freezing')) return 'text-sky-400'
  if (condition.includes('Rain') || condition.includes('Drizzle')) return 'text-blue-500'
  if (condition === 'Fog') return 'text-zinc-400'
  if (condition.includes('Thunderstorm')) return 'text-violet-500'
  return 'text-muted-fg'
}

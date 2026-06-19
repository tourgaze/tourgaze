import { createApp } from 'vue'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { createNotivue } from 'notivue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import AppShell from './components/layout/AppShell.vue'
import ActivityDetailView from './views/ActivityDetailView.vue'
import SettingsView from './views/SettingsView.vue'
import SetupView from './views/SetupView.vue'
import ToursView from './views/ToursView.vue'
import DashboardView from './views/DashboardView.vue'
import InboxView from './views/InboxView.vue'
import AboutView from './views/AboutView.vue'
import CompareView from './views/CompareView.vue'
import MarkersView from './views/MarkersView.vue'
import 'leaflet/dist/leaflet.css'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', component: SetupView, meta: { public: true } },
    {
      path: '/',
      component: AppShell,
      children: [
        // Tours is the browse view — / redirects there.
        { path: '', redirect: '/tours' },
        { path: 'tours', component: ToursView },
        { path: 'tour/:id', component: ActivityDetailView, props: true },
        { path: 'compare/:a/:b', component: CompareView, props: true },
        { path: 'inbox', component: InboxView },
        { path: 'dashboard', component: DashboardView },
        { path: 'markers', component: MarkersView },
        { path: 'settings', component: SettingsView },
        { path: 'about', component: AboutView },
      ],
    },
  ],
})

// First-run gate: on empty DB, redirect to /setup so a profile is created.
router.beforeEach(async (to) => {
  if (to.meta.public) return true
  try {
    const r = await fetch('/api/users/status')
    if (r.ok) {
      const status = await r.json()
      if (!status.initialized) return { path: '/setup' }
    }
  } catch {
    // Backend unreachable — let the request fall through.
  }
  return true
})

const notivue = createNotivue({
  position: 'bottom-right',
  limit: 5,
  notifications: { global: { duration: 4000 } },
})

const app = createApp(App)
app.use(router)
app.use(VueQueryPlugin)
app.use(notivue)
app.mount('#app')

document.getElementById('boot-loader')?.remove()

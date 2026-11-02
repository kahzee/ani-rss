import {createRouter, createWebHashHistory} from 'vue-router'
import SimpleTrackingView from '@/view/home/SimpleTrackingView.vue'

const routes = [
    {
        path: '/',
        redirect: '/follow'
    },
    { path: '/follow', component: SimpleTrackingView },
    {
        path: '/home',
        component: () => import('@/view/home/DashboardView.vue')
    },
    {
        path: '/subscriptions',
        component: () => import('@/view/home/SubscriptionView.vue')
    },
    {
        path: '/downloads',
        component: () => import('@/view/home/TorrentsInfosView.vue')
    },
    {
        path: '/logs',
        component: () => import('@/view/home/LogsView.vue')
    },
    {
        path: '/settings',
        component: () => import('@/view/home/ConfigView.vue')
    }
]

const router = createRouter({
    history: createWebHashHistory(),
    routes
})

export default router

<script setup lang="ts">
import { RouterView } from 'vue-router'
import ToastProvider from '@/components/ui/ToastProvider.vue'
</script>

<template>
  <RouterView />
  <ToastProvider />
</template>

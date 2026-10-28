<script setup>
import { access, initializeAccess, login, logout } from '../auth/access.js';
</script>

<template>
  <main id="main-content" class="login-screen">
    <section class="login-card" aria-labelledby="login-title">
      <a class="login-brand" href="/"><img src="/favicon.svg" alt="" width="44" height="44"><span>TaskFlow</span></a>
      <p class="eyebrow">任务调度控制台</p>
      <h1 id="login-title">{{ access.user ? '账号暂未获得访问权限' : '登录你的工作空间' }}</h1>
      <p class="login-description">{{ access.user ? '请联系管理员，为你的账号分配控制台访问角色。' : '登录后，管理任务计划、跟踪运行记录，查看工作节点状态。' }}</p>
      <p v-if="access.error" class="error-banner" role="alert">{{ access.error }}</p>
      <p v-if="access.loading" class="login-loading" role="status"><span class="spinner"></span>正在连接登录服务…</p>
      <template v-else>
        <button v-if="access.user" class="button primary login-button" @click="logout">退出当前账号</button>
        <button v-else class="button primary login-button" :disabled="access.busy" @click="login"><span v-if="access.busy" class="spinner"></span>{{ access.busy ? '正在打开登录页…' : '登录 TaskFlow' }}<span aria-hidden="true">→</span></button>
        <button v-if="access.error" class="button secondary login-button" :disabled="access.busy" @click="initializeAccess">重新连接</button>
      </template>
      <p class="login-footnote">通过统一身份服务登录。账号或权限问题请联系管理员。</p>
    </section>
    <p class="login-caption">TaskFlow · 让任务管理井然有序</p>
  </main>
</template>

// api/user.ts
import request from './request'

export const registerApi = (d: { username: string; password: string; nickname?: string }) =>
  request.post('/user/register', d)

export const loginApi = (d: { username: string; password: string }) =>
  request.post('/user/login', d)

export const getMeApi = () => request.get('/user/me')

export const updateProfileApi = (d: { nickname: string; email?: string }) =>
  request.put('/user/profile', d)

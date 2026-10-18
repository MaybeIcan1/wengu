import request from './request'

export const createTag = (d: { name: string }) => request.post('/tag', d)
export const listTag = () => request.get('/tag/list')

import request from './request'

export const createCategory = (d: { name: string }) => request.post('/category', d)
export const listCategory = () => request.get('/category/list')

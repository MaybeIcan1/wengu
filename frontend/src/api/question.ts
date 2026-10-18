import request from './request'

export const pageQuestion = (q: any) => request.get('/question/page', { params: q })
export const detailQuestion = (id: number | string) => request.get(`/question/${id}`)
export const createQuestion = (d: any) => request.post('/question', d)
export const updateQuestion = (d: any) => request.put('/question', d)
export const deleteQuestion = (id: number | string) => request.delete(`/question/${id}`)

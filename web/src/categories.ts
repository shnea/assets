import {createContext} from 'react';
import {categories} from './api';
export const CategoryOptions=createContext<string[]>(categories);
